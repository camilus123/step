package si.marko.step.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * What the app remembers between sensor readings.
 *
 * @param lastRaw the sensor's cumulative value ("steps since boot") at the last accounted reading.
 * @param lastEventWallMillis when the sensor took that reading, as UTC epoch milliseconds.
 *        Stored in UTC so a time zone change cannot corrupt it.
 * @param boot which boot of the phone [lastRaw] belongs to.
 */
data class CounterState(val lastRaw: Long, val lastEventWallMillis: Long, val boot: BootInfo)

/** One hour of one calendar day, in the local time that applied when the steps were recorded. */
data class BucketKey(val epochDay: Long, val hour: Int)

data class Outcome(
    val state: CounterState,
    /** Steps to add per hourly bucket. Never negative; empty when nothing new was walked. */
    val credits: Map<BucketKey, Long>,
    val rebooted: Boolean,
    /** Something worth writing to the event log, or null for an ordinary reading. */
    val note: String?,
)

/**
 * Turns raw step counter readings into steps per hourly bucket. Pure Kotlin, no Android.
 *
 * The rules:
 *
 * - The sensor reports "steps since boot". Only the difference to the previous reading counts.
 * - The very first reading only sets the baseline. Steps walked before the app first saw the
 *   sensor are unknown and are not invented.
 * - The steps between two readings were walked somewhere between the moment the sensor took the
 *   previous reading and the moment it took the new one. They are spread evenly over the end of
 *   that span, over at most [MAX_MILLIS_PER_STEP] per step, and cut at hour boundaries. So ten
 *   steps after a night on the desk land in the minute they were walked, not across the night,
 *   while thousands of steps from a long gap (the service was not running) are spread over the
 *   hours of that gap. Nothing is ever dropped.
 * - The spread never reaches back further than the start of the day before the new reading.
 * - After a reboot the counter restarts at zero, so everything it shows was walked since boot
 *   and is credited from the boot time onward. Steps walked between the last reading before the
 *   reboot and the shutdown are lost if the app was not listening then; nothing can recover them.
 * - A bucket belongs to the local date and hour at the time of recording. Changing the time zone
 *   later does not move old steps to another day.
 */
object StepAccountant {

    /** Steps spread over more time than this are mentioned in the event log as a credited gap. */
    const val GAP_NOTE_THRESHOLD_MS = 10 * 60_000L

    /**
     * The slowest pace the spread assumes: one step every three seconds, a very slow stroll.
     * The sensor stamps a reading with the time of the last counted step, so the steps of a
     * reading ended at that time; this bounds how far back before it they can plausibly reach.
     * The 17T reports the first ten or so steps of a walk in one go, which is what made a
     * purely even spread put single steps into hours where nobody walked.
     */
    const val MAX_MILLIS_PER_STEP = 3_000L

    private val NOTE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun process(
        previous: CounterState?,
        raw: Long,
        eventWallMillis: Long,
        boot: BootInfo,
        zone: ZoneId,
    ): Outcome {
        if (previous == null) {
            return Outcome(
                state = CounterState(raw, eventWallMillis, boot),
                credits = emptyMap(),
                rebooted = false,
                note = "Baseline set: raw=$raw",
            )
        }

        val rebooted = BootTracker.rebootedSince(previous.boot, boot)
        val steps: Long
        val startMillis: Long
        var note: String? = null
        when {
            rebooted -> {
                steps = raw
                startMillis = maxOf(previous.lastEventWallMillis, boot.bootTimeMillis)
                note = "Reboot detected (boot count ${previous.boot.bootCount} -> ${boot.bootCount}): " +
                    "new baseline, crediting $raw steps counted since boot"
            }
            raw < previous.lastRaw -> {
                // The counter went backwards within one boot. Should not happen; treat it like
                // a restart of the counter so that nothing negative or huge is produced.
                steps = raw
                startMillis = previous.lastEventWallMillis
                note = "Counter went down without a reboot (${previous.lastRaw} -> $raw): " +
                    "new baseline, crediting $raw steps"
            }
            else -> {
                steps = raw - previous.lastRaw
                startMillis = previous.lastEventWallMillis
            }
        }

        if (steps == 0L && !rebooted && raw == previous.lastRaw) {
            return Outcome(previous, emptyMap(), rebooted = false, note = null)
        }

        val credits = distribute(steps, startMillis, eventWallMillis, zone)
        val spreadFrom = spreadStart(steps, startMillis, eventWallMillis, zone)
        if (note == null && steps > 0 && eventWallMillis - spreadFrom > GAP_NOTE_THRESHOLD_MS) {
            note = "Gap credited: $steps steps between ${format(spreadFrom, zone)} and " +
                "${format(eventWallMillis, zone)}, into ${credits.size} hourly bucket(s)"
        }
        return Outcome(
            state = CounterState(raw, eventWallMillis, if (rebooted) boot else previous.boot),
            credits = credits,
            rebooted = rebooted,
            note = note,
        )
    }

    /**
     * Where the spread of [steps] begins: the start of the span, but no earlier than
     * [MAX_MILLIS_PER_STEP] per step before its end and no earlier than the start of the day
     * before its end.
     */
    fun spreadStart(steps: Long, startMillis: Long, endMillis: Long, zone: ZoneId): Long {
        val end = Instant.ofEpochMilli(endMillis).atZone(zone)
        val earliestDay = end.toLocalDate().minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val earliestPace = endMillis - steps * MAX_MILLIS_PER_STEP
        return maxOf(startMillis, earliestDay, earliestPace)
    }

    /**
     * Spreads [steps] evenly over the time from [spreadStart] to [endMillis] and returns how many
     * fall into each hourly bucket. The total always equals [steps] exactly.
     */
    fun distribute(steps: Long, startMillis: Long, endMillis: Long, zone: ZoneId): Map<BucketKey, Long> {
        if (steps <= 0) return emptyMap()
        val end = Instant.ofEpochMilli(endMillis).atZone(zone)
        val start = spreadStart(steps, startMillis, endMillis, zone)
        // No usable time span (same instant, or the clock was set back): use the reading's hour.
        if (start >= endMillis) return mapOf(keyOf(end) to steps)

        // Milliseconds of the span that fall into each bucket.
        val weights = LinkedHashMap<BucketKey, Long>()
        var cursor = Instant.ofEpochMilli(start).atZone(zone)
        while (cursor.toInstant().toEpochMilli() < endMillis) {
            val cursorMillis = cursor.toInstant().toEpochMilli()
            val nextHour = cursor.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
            val segmentEnd = minOf(nextHour, endMillis)
            weights.merge(keyOf(cursor), segmentEnd - cursorMillis, Long::plus)
            cursor = Instant.ofEpochMilli(segmentEnd).atZone(zone)
        }

        // Largest remainder method: whole shares first, then the leftover steps go to the
        // buckets with the largest fractional share (later bucket wins a tie).
        val total = endMillis - start
        val keys = weights.keys.toList()
        val shares = LongArray(keys.size) { steps * weights.getValue(keys[it]) / total }
        val remainders = LongArray(keys.size) { steps * weights.getValue(keys[it]) % total }
        var leftover = steps - shares.sum()
        val order = keys.indices.sortedWith(compareByDescending<Int> { remainders[it] }.thenByDescending { it })
        var i = 0
        while (leftover > 0) {
            shares[order[i % order.size]]++
            leftover--
            i++
        }
        return keys.indices.filter { shares[it] > 0 }.associate { keys[it] to shares[it] }
    }

    private fun keyOf(time: ZonedDateTime) = BucketKey(time.toLocalDate().toEpochDay(), time.hour)

    private fun format(millis: Long, zone: ZoneId) = NOTE_TIME.format(Instant.ofEpochMilli(millis).atZone(zone))
}
