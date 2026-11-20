package si.marko.step.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StatsTest {

    private fun d(text: String) = LocalDate.parse(text)
    private val goals = GoalHistory(emptyMap(), initial = 10_000)

    // ---- periods ---------------------------------------------------------------------------

    @Test
    fun `weeks run from Monday to Sunday`() {
        // 20 Nov 2026 is a Friday.
        val week = Period.containing(PeriodKind.WEEK, d("2026-11-20"))
        assertEquals(d("2026-11-16"), week.start)
        assertEquals(d("2026-11-22"), week.end)
        // A Monday and a Sunday belong to their own week.
        assertEquals(week, Period.containing(PeriodKind.WEEK, d("2026-11-16")))
        assertEquals(week, Period.containing(PeriodKind.WEEK, d("2026-11-22")))
    }

    @Test
    fun `a week can span two years`() {
        val week = Period.containing(PeriodKind.WEEK, d("2027-01-01"))
        assertEquals(d("2026-12-28"), week.start)
        assertEquals(d("2027-01-03"), week.end)
    }

    @Test
    fun `months and years cover whole calendar units`() {
        val february = Period.containing(PeriodKind.MONTH, d("2028-02-10")) // leap year
        assertEquals(d("2028-02-01"), february.start)
        assertEquals(d("2028-02-29"), february.end)
        val year = Period.containing(PeriodKind.YEAR, d("2026-11-20"))
        assertEquals(d("2026-01-01"), year.start)
        assertEquals(d("2026-12-31"), year.end)
    }

    @Test
    fun `shifting moves by whole periods in both directions`() {
        val week = Period.containing(PeriodKind.WEEK, d("2026-11-20"))
        assertEquals(d("2026-11-09"), week.shifted(-1).start)
        assertEquals(d("2026-11-23"), week.shifted(1).start)
        val january = Period.containing(PeriodKind.MONTH, d("2026-01-31"))
        assertEquals(d("2026-02-28"), january.shifted(1).end)
        assertEquals(d("2025-12-01"), january.shifted(-1).start)
        assertEquals(d("2024-01-01"), Period.containing(PeriodKind.YEAR, d("2026-05-05")).shifted(-2).start)
    }

    @Test
    fun `contains includes both ends`() {
        val week = Period.containing(PeriodKind.WEEK, d("2026-11-20"))
        assertTrue(d("2026-11-16") in week)
        assertTrue(d("2026-11-22") in week)
        assertFalse(d("2026-11-23") in week)
    }

    // ---- goal history ----------------------------------------------------------------------

    @Test
    fun `past days keep the goal that applied on them`() {
        val history = GoalHistory(mapOf(d("2026-11-10") to 8_000, d("2026-11-20") to 12_000), initial = 10_000)
        assertEquals(10_000, history.goalOn(d("2026-11-09")))
        assertEquals(8_000, history.goalOn(d("2026-11-10")))
        assertEquals(8_000, history.goalOn(d("2026-11-19")))
        assertEquals(12_000, history.goalOn(d("2026-11-20")))
        assertEquals(12_000, history.goalOn(d("2027-01-01")))
    }

    // ---- week ------------------------------------------------------------------------------

    @Test
    fun `week has seven bars and empty days are zero`() {
        val week = Period.containing(PeriodKind.WEEK, d("2026-11-20"))
        val stats = StatsCalculator.compute(
            week,
            mapOf(d("2026-11-18") to 5_125L, d("2026-11-19") to 11_000L, d("2026-11-20") to 4_502L),
            today = d("2026-11-20"),
            firstRecordedDay = d("2026-11-18"),
            goals = goals,
        )
        assertEquals(listOf(0L, 0L, 5_125L, 11_000L, 4_502L, 0L, 0L), stats.bars.map { it.steps })
        assertEquals(d("2026-11-16"), stats.bars.first().start)
        assertEquals(20_627L, stats.total)
        // Averaged over Wed, Thu, Fri: not the days before the first recording, not the future.
        assertEquals(3, stats.countedDays)
        assertEquals(6_876L, stats.dailyAverage)
        assertEquals(BestDay(d("2026-11-19"), 11_000L), stats.bestDay)
        assertEquals(1, stats.goalDays)
    }

    @Test
    fun `a day without steps in between counts as zero in the average`() {
        val week = Period.containing(PeriodKind.WEEK, d("2026-11-11"))
        val stats = StatsCalculator.compute(
            week,
            mapOf(d("2026-11-09") to 9_000L, d("2026-11-11") to 3_000L),
            today = d("2026-11-20"),
            firstRecordedDay = d("2026-11-01"),
            goals = goals,
        )
        assertEquals(7, stats.countedDays)
        assertEquals(1_714L, stats.dailyAverage) // 12 000 / 7
    }

    @Test
    fun `a period without any data has zeros and no best day`() {
        val week = Period.containing(PeriodKind.WEEK, d("2026-10-01"))
        val stats = StatsCalculator.compute(week, emptyMap(), d("2026-11-20"), d("2026-11-18"), goals)
        assertEquals(7, stats.bars.size)
        assertEquals(0L, stats.total)
        assertEquals(0L, stats.dailyAverage)
        assertEquals(0, stats.countedDays)
        assertNull(stats.bestDay)
        assertEquals(0, stats.goalDays)
    }

    @Test
    fun `nothing recorded yet at all`() {
        val week = Period.containing(PeriodKind.WEEK, d("2026-11-20"))
        val stats = StatsCalculator.compute(week, emptyMap(), d("2026-11-20"), firstRecordedDay = null, goals = goals)
        assertEquals(0, stats.countedDays)
        assertEquals(0L, stats.dailyAverage)
    }

    @Test
    fun `goal days use the goal of each day`() {
        val week = Period.containing(PeriodKind.WEEK, d("2026-11-20"))
        val history = GoalHistory(mapOf(d("2026-11-19") to 5_000), initial = 10_000)
        val stats = StatsCalculator.compute(
            week,
            mapOf(d("2026-11-17") to 6_000L, d("2026-11-18") to 10_000L, d("2026-11-19") to 6_000L, d("2026-11-20") to 4_999L),
            today = d("2026-11-20"),
            firstRecordedDay = d("2026-11-16"),
            goals = history,
        )
        // Tue 6000 < 10000 no; Wed 10000 yes; Thu 6000 >= 5000 yes; Fri 4999 < 5000 no.
        assertEquals(2, stats.goalDays)
        // The line shows the goal of the latest day that has begun: today, Friday.
        assertEquals(5_000, stats.goalLine)
        // For a week that is over, that is its Sunday.
        val earlier = StatsCalculator.compute(week.shifted(-1), emptyMap(), d("2026-11-20"), d("2026-11-01"), history)
        assertEquals(10_000, earlier.goalLine)
    }

    // ---- month and year --------------------------------------------------------------------

    @Test
    fun `month has one bar per day`() {
        val november = Period.containing(PeriodKind.MONTH, d("2026-11-20"))
        val stats = StatsCalculator.compute(november, mapOf(d("2026-11-30") to 7L), d("2026-11-30"), d("2026-11-01"), goals)
        assertEquals(30, stats.bars.size)
        assertEquals(7L, stats.bars.last().steps)
        assertEquals(30, stats.countedDays)
    }

    @Test
    fun `year has twelve monthly bars but best day and goal days stay daily`() {
        val year = Period.containing(PeriodKind.YEAR, d("2026-11-20"))
        val stats = StatsCalculator.compute(
            year,
            mapOf(
                d("2026-10-30") to 12_000L,
                d("2026-10-31") to 4_000L,
                d("2026-11-01") to 10_500L,
                d("2027-01-01") to 99_999L, // outside the year: ignored
            ),
            today = d("2026-11-20"),
            firstRecordedDay = d("2026-10-30"),
            goals = goals,
        )
        assertEquals(12, stats.bars.size)
        assertEquals(d("2026-10-01"), stats.bars[9].start)
        assertEquals(16_000L, stats.bars[9].steps)
        assertEquals(10_500L, stats.bars[10].steps)
        assertEquals(26_500L, stats.total)
        assertEquals(BestDay(d("2026-10-30"), 12_000L), stats.bestDay)
        assertEquals(2, stats.goalDays)
        assertEquals(22, stats.countedDays) // 30 Oct to 20 Nov
        assertEquals(1_205L, stats.dailyAverage) // 26 500 / 22 = 1204.5, rounded
    }

    // ---- today -----------------------------------------------------------------------------

    @Test
    fun `hourly view always has 24 values`() {
        val hours = StatsCalculator.hourly(mapOf(7 to 300L, 18 to 2_000L))
        assertEquals(24, hours.size)
        assertEquals(300L, hours[7])
        assertEquals(2_000L, hours[18])
        assertEquals(2_300L, hours.sum())
    }
}
