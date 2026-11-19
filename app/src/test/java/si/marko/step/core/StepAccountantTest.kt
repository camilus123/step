package si.marko.step.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class StepAccountantTest {

    private val zone = ZoneId.of("Europe/Ljubljana")
    private val boot = BootInfo(bootCount = 14, bootTimeMillis = at("2026-11-17T06:00:00"))

    private fun at(local: String, z: ZoneId = zone): Long =
        LocalDateTime.parse(local).atZone(z).toInstant().toEpochMilli()

    private fun key(date: String, hour: Int) = BucketKey(LocalDate.parse(date).toEpochDay(), hour)

    private fun state(raw: Long, time: String, b: BootInfo = boot) = CounterState(raw, at(time), b)

    // ---- baseline and ordinary counting ----------------------------------------------------

    @Test
    fun `first reading sets the baseline and credits nothing`() {
        val out = StepAccountant.process(null, 23_001, at("2026-11-17T21:46:00"), boot, zone)
        assertTrue(out.credits.isEmpty())
        assertEquals(CounterState(23_001, at("2026-11-17T21:46:00"), boot), out.state)
        assertFalse(out.rebooted)
    }

    @Test
    fun `steps within one hour land in that hour`() {
        val out = StepAccountant.process(state(1000, "2026-11-17T14:10:00"), 1042, at("2026-11-17T14:10:30"), boot, zone)
        assertEquals(mapOf(key("2026-11-17", 14) to 42L), out.credits)
        assertEquals(1042, out.state.lastRaw)
        assertEquals(at("2026-11-17T14:10:30"), out.state.lastEventWallMillis)
        assertNull(out.note)
    }

    @Test
    fun `an unchanged value changes nothing`() {
        val previous = state(1000, "2026-11-17T14:10:00")
        val out = StepAccountant.process(previous, 1000, at("2026-11-17T14:10:00"), boot, zone)
        assertTrue(out.credits.isEmpty())
        assertEquals(previous, out.state)
        assertNull(out.note)
    }

    @Test
    fun `steps across an hour boundary are split by time`() {
        // 30 s span, 20 s before 15:00 and 10 s after: 30 steps -> 20 + 10.
        val out = StepAccountant.process(state(500, "2026-11-17T14:59:40"), 530, at("2026-11-17T15:00:10"), boot, zone)
        assertEquals(mapOf(key("2026-11-17", 14) to 20L, key("2026-11-17", 15) to 10L), out.credits)
    }

    // ---- midnight --------------------------------------------------------------------------

    @Test
    fun `steps across midnight are split between the two days`() {
        // Previous reading 23:50, new one 00:03 with 260 steps: 10 min before, 3 min after.
        val out = StepAccountant.process(state(9000, "2026-11-17T23:50:00"), 9260, at("2026-11-18T00:03:00"), boot, zone)
        assertEquals(mapOf(key("2026-11-17", 23) to 200L, key("2026-11-18", 0) to 60L), out.credits)
    }

    @Test
    fun `a few steps just after midnight stay on the new day`() {
        // 20 steps reported at 00:00:30 after a quiet evening: at most 60 s back, so 23:59:30.
        val out = StepAccountant.process(state(9000, "2026-11-17T21:00:00"), 9020, at("2026-11-18T00:00:30"), boot, zone)
        assertEquals(mapOf(key("2026-11-17", 23) to 10L, key("2026-11-18", 0) to 10L), out.credits)
    }

    @Test
    fun `a span ending exactly on the hour stays in the earlier hour`() {
        val out = StepAccountant.process(state(0, "2026-11-17T23:59:00"), 60, at("2026-11-18T00:00:00"), boot, zone)
        assertEquals(mapOf(key("2026-11-17", 23) to 60L), out.credits)
    }

    // ---- gaps ------------------------------------------------------------------------------

    @Test
    fun `a gap is spread evenly over its hours and nothing is dropped`() {
        // Service dead from 10:30 to 13:30, 6000 steps: 30 min, 60, 60, 30 min.
        val out = StepAccountant.process(state(2000, "2026-11-17T10:30:00"), 8000, at("2026-11-17T13:30:00"), boot, zone)
        assertEquals(
            mapOf(
                key("2026-11-17", 10) to 1000L,
                key("2026-11-17", 11) to 2000L,
                key("2026-11-17", 12) to 2000L,
                key("2026-11-17", 13) to 1000L,
            ),
            out.credits,
        )
        assertNotNull(out.note)
        assertTrue(out.note!!.startsWith("Gap credited: 6000 steps"))
    }

    @Test
    fun `a gap with few steps is not smeared over hours nobody walked in`() {
        // Seen on the 17T: phone on the desk from 22:58, ten steps reported at 09:54 next morning.
        val out = StepAccountant.process(state(4000, "2026-11-18T22:58:00"), 4010, at("2026-11-19T09:54:29"), boot, zone)
        assertEquals(mapOf(key("2026-11-19", 9) to 10L), out.credits)
        assertNull(out.note)
    }

    @Test
    fun `a gap with a moderate walk reaches back three seconds per step`() {
        // 1200 steps after a six hour gap: spread over the last hour only (1200 x 3 s).
        val out = StepAccountant.process(state(0, "2026-11-17T08:00:00"), 1200, at("2026-11-17T14:30:00"), boot, zone)
        assertEquals(mapOf(key("2026-11-17", 13) to 600L, key("2026-11-17", 14) to 600L), out.credits)
        assertNotNull(out.note)
    }

    @Test
    fun `rounding never loses or invents a step`() {
        // 7000 steps over three equal hours cannot be split evenly.
        val credits = StepAccountant.distribute(7000, at("2026-11-17T10:00:00"), at("2026-11-17T13:00:00"), zone)
        assertEquals(7000L, credits.values.sum())
        assertEquals(setOf(key("2026-11-17", 10), key("2026-11-17", 11), key("2026-11-17", 12)), credits.keys)
        assertTrue(credits.values.all { it in 2333L..2334L })
    }

    @Test
    fun `totals are exact for awkward spans`() {
        for (steps in listOf(1L, 2L, 13L, 999L, 12_345L, 54_321L)) {
            val credits = StepAccountant.distribute(steps, at("2026-11-16T22:17:31"), at("2026-11-17T09:41:07"), zone)
            assertEquals(steps, credits.values.sum())
            assertTrue(credits.values.all { it > 0 })
        }
    }

    @Test
    fun `a very long gap is capped at the previous day`() {
        // Last reading five days ago, 72 000 steps since: spread only over yesterday and today.
        val out = StepAccountant.process(state(100, "2026-11-12T08:00:00"), 72_100, at("2026-11-17T12:00:00"), boot, zone)
        val days = out.credits.keys.map { it.epochDay }.toSet()
        assertEquals(
            setOf(LocalDate.parse("2026-11-16").toEpochDay(), LocalDate.parse("2026-11-17").toEpochDay()),
            days,
        )
        assertEquals(72_000L, out.credits.values.sum())
        // 36 hours, 2000 steps each.
        assertEquals(36, out.credits.size)
        assertTrue(out.credits.values.all { it == 2000L })
    }

    // ---- reboot ----------------------------------------------------------------------------

    @Test
    fun `reboot starts a new baseline and credits steps since boot from boot time`() {
        val newBoot = BootInfo(15, at("2026-11-17T23:22:25"))
        // Before the reboot the counter stood at 23107. After it, 120 steps by 23:30.
        val out = StepAccountant.process(state(23_107, "2026-11-17T23:20:00"), 120, at("2026-11-17T23:30:00"), newBoot, zone)
        assertTrue(out.rebooted)
        assertEquals(mapOf(key("2026-11-17", 23) to 120L), out.credits)
        assertEquals(CounterState(120, at("2026-11-17T23:30:00"), newBoot), out.state)
    }

    @Test
    fun `reboot with zero steps since boot credits nothing but moves the baseline`() {
        val newBoot = BootInfo(15, at("2026-11-17T23:22:25"))
        val out = StepAccountant.process(state(23_107, "2026-11-17T23:20:00"), 0, at("2026-11-17T23:23:17"), newBoot, zone)
        assertTrue(out.rebooted)
        assertTrue(out.credits.isEmpty())
        assertEquals(0, out.state.lastRaw)
        assertEquals(newBoot, out.state.boot)
    }

    @Test
    fun `reboot is recognised even when the new value is higher than the old one`() {
        // Old boot ended at 50. New boot already shows 400. Without the boot count this would
        // look like 350 ordinary steps; with it, all 400 are credited.
        val newBoot = BootInfo(15, at("2026-11-17T08:00:00"))
        val out = StepAccountant.process(state(50, "2026-11-17T07:00:00"), 400, at("2026-11-17T08:40:00"), newBoot, zone)
        assertTrue(out.rebooted)
        assertEquals(400L, out.credits.values.sum())
        assertEquals(setOf(key("2026-11-17", 8)), out.credits.keys)
    }

    @Test
    fun `steps after a reboot are not smeared back before the boot`() {
        val newBoot = BootInfo(15, at("2026-11-17T12:00:00"))
        // 3000 steps would reach back 150 minutes, to 10:30; the boot at 12:00 limits it.
        val out = StepAccountant.process(state(5000, "2026-11-17T08:00:00"), 3000, at("2026-11-17T13:00:00"), newBoot, zone)
        assertEquals(mapOf(key("2026-11-17", 12) to 3000L), out.credits)
    }

    @Test
    fun `counter going down within one boot never produces negative steps`() {
        val out = StepAccountant.process(state(5000, "2026-11-17T08:00:00"), 30, at("2026-11-17T08:05:00"), boot, zone)
        assertFalse(out.rebooted)
        assertEquals(30L, out.credits.values.sum())
        assertEquals(30, out.state.lastRaw)
        assertNotNull(out.note)
    }

    @Test
    fun `the stored boot identity stays stable within one boot`() {
        // The computed boot time jitters by a few ms between measurements.
        val jittered = boot.copy(bootTimeMillis = boot.bootTimeMillis + 40)
        val out = StepAccountant.process(state(10, "2026-11-17T09:00:00"), 20, at("2026-11-17T09:00:10"), jittered, zone)
        assertEquals(boot, out.state.boot)
    }

    // ---- clock and time zone ---------------------------------------------------------------

    @Test
    fun `clock set back puts the steps in the hour of the new reading`() {
        val out = StepAccountant.process(state(100, "2026-11-17T15:00:00"), 150, at("2026-11-17T14:20:00"), boot, zone)
        assertEquals(mapOf(key("2026-11-17", 14) to 50L), out.credits)
        assertEquals(at("2026-11-17T14:20:00"), out.state.lastEventWallMillis)
    }

    @Test
    fun `buckets use the zone in effect when the reading is processed`() {
        // Same instants, processed in Tokyo: 14:10 Ljubljana is 22:10 in Tokyo.
        val tokyo = ZoneId.of("Asia/Tokyo")
        val out = StepAccountant.process(state(0, "2026-11-17T14:10:00"), 10, at("2026-11-17T14:10:20"), boot, tokyo)
        assertEquals(mapOf(key("2026-11-17", 22) to 10L), out.credits)
    }

    @Test
    fun `autumn clock change puts both 2 o'clock hours into one bucket`() {
        // 25 Oct 2026: 03:00 CEST becomes 02:00 CET. From 01:30 CEST to 02:30 CET is two hours.
        val start = at("2026-10-25T01:30:00")                     // CEST, first pass
        val end = start + 2 * 3_600_000L                           // 02:30 CET, second pass
        val credits = StepAccountant.distribute(3600, start, end, zone)
        assertEquals(mapOf(key("2026-10-25", 1) to 900L, key("2026-10-25", 2) to 2700L), credits)
    }

    @Test
    fun `spring clock change skips the missing hour`() {
        // 29 Mar 2026: 02:00 CET becomes 03:00 CEST. 01:30 to 03:30 local is one real hour.
        val start = at("2026-03-29T01:30:00")
        val end = start + 3_600_000L
        val credits = StepAccountant.distribute(1800, start, end, zone)
        assertEquals(mapOf(key("2026-03-29", 1) to 900L, key("2026-03-29", 3) to 900L), credits)
    }
}
