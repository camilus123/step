package si.marko.step.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class PeriodKind { WEEK, MONTH, YEAR }

/** A calendar week (Monday to Sunday), month or year. Both ends inclusive. */
data class Period(val kind: PeriodKind, val start: LocalDate, val end: LocalDate) {

    operator fun contains(date: LocalDate): Boolean = date >= start && date <= end

    /** The period [count] steps later (earlier when negative): next week, previous month, ... */
    fun shifted(count: Long): Period = when (kind) {
        PeriodKind.WEEK -> containing(kind, start.plusWeeks(count))
        PeriodKind.MONTH -> containing(kind, start.plusMonths(count))
        PeriodKind.YEAR -> containing(kind, start.plusYears(count))
    }

    companion object {
        fun containing(kind: PeriodKind, date: LocalDate): Period = when (kind) {
            PeriodKind.WEEK -> {
                val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                Period(kind, monday, monday.plusDays(6))
            }
            PeriodKind.MONTH -> Period(kind, date.withDayOfMonth(1), date.with(TemporalAdjusters.lastDayOfMonth()))
            PeriodKind.YEAR -> Period(kind, date.withDayOfYear(1), date.with(TemporalAdjusters.lastDayOfYear()))
        }
    }
}

/**
 * The daily goal over time. When the goal is changed, past days keep the goal that applied on
 * them, so history does not rewrite itself.
 *
 * @param changes "from this day on the goal is this", in any order.
 * @param initial the goal before the first change.
 */
class GoalHistory(changes: Map<LocalDate, Int>, private val initial: Int = Goal.DEFAULT) {
    private val sorted = changes.toSortedMap()

    fun goalOn(date: LocalDate): Int = sorted.headMap(date.plusDays(1)).let { if (it.isEmpty()) initial else it[it.lastKey()]!! }
}

/** One bar of a chart: a day (week and month view) or a whole month (year view). */
data class StatBar(val start: LocalDate, val steps: Long)

data class BestDay(val date: LocalDate, val steps: Long)

data class PeriodStats(
    val period: Period,
    val bars: List<StatBar>,
    val total: Long,
    /** Total divided by [countedDays], rounded. */
    val dailyAverage: Long,
    /** The day with the most steps, or null when the period has no steps. */
    val bestDay: BestDay?,
    /** Days on which that day's goal was reached. */
    val goalDays: Int,
    /** Days the average is taken over; see [StatsCalculator]. */
    val countedDays: Int,
)

/**
 * Turns daily totals into the figures of one period. Pure Kotlin, unit tested.
 *
 * Which days count for the average and for "goal reached on x of y days": the days of the
 * period that have already begun (not the future) and are not earlier than the first day the app
 * ever recorded steps. A day without any steps in between counts as a day with zero steps. So a
 * week that is three days old is averaged over three days, and the first week of using the app
 * is not dragged down by the days before it was installed.
 */
object StatsCalculator {

    fun compute(
        period: Period,
        dayTotals: Map<LocalDate, Long>,
        today: LocalDate,
        firstRecordedDay: LocalDate?,
        goals: GoalHistory,
    ): PeriodStats {
        val days = generateSequence(period.start) { it.plusDays(1) }.takeWhile { it <= period.end }.toList()
        fun stepsOn(day: LocalDate) = dayTotals[day] ?: 0L

        val bars = when (period.kind) {
            PeriodKind.WEEK, PeriodKind.MONTH -> days.map { StatBar(it, stepsOn(it)) }
            PeriodKind.YEAR -> days.groupBy { it.withDayOfMonth(1) }.map { (month, monthDays) ->
                StatBar(month, monthDays.sumOf(::stepsOn))
            }
        }
        val total = days.sumOf(::stepsOn)
        val counted = if (firstRecordedDay == null) emptyList() else days.filter { it <= today && it >= firstRecordedDay }
        val best = days.filter { stepsOn(it) > 0 }.maxByOrNull(::stepsOn)

        return PeriodStats(
            period = period,
            bars = bars,
            total = total,
            dailyAverage = if (counted.isEmpty()) 0 else Math.round(total.toDouble() / counted.size),
            bestDay = best?.let { BestDay(it, stepsOn(it)) },
            goalDays = days.count { Goal.reached(stepsOn(it), goals.goalOn(it)) },
            countedDays = counted.size,
        )
    }

    /** 24 values, one per hour of the day, from whatever hours have steps stored. */
    fun hourly(stored: Map<Int, Long>): List<Long> = (0..23).map { stored[it] ?: 0L }
}
