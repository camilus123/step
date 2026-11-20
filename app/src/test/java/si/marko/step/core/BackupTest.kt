package si.marko.step.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class BackupTest {

    private fun d(text: String) = LocalDate.parse(text)
    private fun key(date: String, hour: Int) = BucketKey(d(date).toEpochDay(), hour)

    private val sample = BackupData(
        hours = listOf(
            HourRecord(d("2026-11-19"), 9, 10),
            HourRecord(d("2026-11-18"), 12, 340),
            HourRecord(d("2026-11-18"), 15, 511),
        ),
        goals = listOf(GoalRecord(d("2026-11-20"), 8_000)),
    )

    private fun failsWith(text: String, expectedPart: String) {
        try {
            BackupFormat.fromJson(text)
            fail("expected a BackupFormatException")
        } catch (e: BackupFormatException) {
            assertTrue("message was: ${e.message}", e.message!!.contains(expectedPart))
        }
    }

    // ---- format ----------------------------------------------------------------------------

    @Test
    fun `export and import give back the same data`() {
        val json = BackupFormat.toJson(sample, "2026-11-20T21:50:00+01:00", "0.5.11")
        val read = BackupFormat.fromJson(json)
        assertEquals(sample.hours.sortedWith(compareBy({ it.date }, { it.hour })), read.hours)
        assertEquals(sample.goals, read.goals)
    }

    @Test
    fun `the file carries its format name and version`() {
        val json = BackupFormat.toJson(sample, "2026-11-20T21:50:00+01:00", "0.5.11")
        assertTrue(json.contains("\"format\":\"step-backup\""))
        assertTrue(json.contains("\"formatVersion\":1"))
        assertTrue(json.contains("\"appVersion\":\"0.5.11\""))
    }

    @Test
    fun `an empty history exports and imports`() {
        val read = BackupFormat.fromJson(BackupFormat.toJson(BackupData(emptyList(), emptyList()), "x", "y"))
        assertTrue(read.hours.isEmpty())
        assertTrue(read.goals.isEmpty())
    }

    @Test
    fun `unknown extra fields are ignored and missing lists are empty`() {
        val read = BackupFormat.fromJson(
            """{"format":"step-backup","formatVersion":1,"somethingNew":{"a":1},
               "hours":[{"date":"2026-11-18","hour":0,"steps":5,"note":"x"}]}"""
        )
        assertEquals(listOf(HourRecord(d("2026-11-18"), 0, 5)), read.hours)
        assertTrue(read.goals.isEmpty())
    }

    @Test
    fun `things that are not a backup are refused with a clear message`() {
        failsWith("hello", "not a Step backup file")
        failsWith("[1,2,3]", "not a Step backup file")
        failsWith("""{"format":"something-else","formatVersion":1}""", "not a Step backup file")
        failsWith("""{"format":"step-backup"}""", "no format version")
    }

    @Test
    fun `a backup from a newer app version is refused`() {
        failsWith("""{"format":"step-backup","formatVersion":2,"hours":[]}""", "newer version")
    }

    @Test
    fun `bad entries are refused instead of imported halfway`() {
        fun file(entry: String) = """{"format":"step-backup","formatVersion":1,"hours":[$entry]}"""
        failsWith(file("""{"date":"2026-13-40","hour":1,"steps":5}"""), "no valid date")
        failsWith(file("""{"date":"2026-11-18","hour":24,"steps":5}"""), "no valid hour")
        failsWith(file("""{"date":"2026-11-18","hour":1,"steps":-5}"""), "no valid step count")
        failsWith(file("""{"date":"2026-11-18","hour":1,"steps":"many"}"""), "no valid step count")
        failsWith(file("""7"""), "malformed")
        failsWith("""{"format":"step-backup","formatVersion":1,"hours":{}}""", "malformed")
        failsWith("""{"format":"step-backup","formatVersion":1,"goals":[{"from":"2026-11-18","goal":0}]}""", "no valid goal")
    }

    @Test
    fun `csv has a header and one sorted line per hour`() {
        assertEquals(
            "date,hour,steps\n2026-11-18,12,340\n2026-11-18,15,511\n2026-11-19,9,10\n",
            BackupFormat.toCsv(sample),
        )
    }

    // ---- merge -----------------------------------------------------------------------------

    @Test
    fun `importing into an empty app adds everything`() {
        val plan = BackupMerge.plan(emptyMap(), emptySet(), sample)
        assertEquals(3, plan.hoursToWrite.size)
        assertEquals(1, plan.goalsToWrite.size)
        assertEquals(
            ImportSummary(
                hoursInFile = 3, hoursAdded = 3, hoursRaised = 0, hoursUnchanged = 0, stepsGained = 861,
                goalsAdded = 1, firstDay = d("2026-11-18"), lastDay = d("2026-11-19"),
            ),
            plan.summary,
        )
    }

    @Test
    fun `for each hour the larger value wins`() {
        val existing = mapOf(
            key("2026-11-18", 12) to 100L, // file has 340: raised
            key("2026-11-18", 15) to 900L, // file has 511: kept
            key("2026-11-17", 8) to 50L,   // not in the file: untouched
        )
        val plan = BackupMerge.plan(existing, emptySet(), sample)
        assertEquals(
            setOf(HourRecord(d("2026-11-18"), 12, 340), HourRecord(d("2026-11-19"), 9, 10)),
            plan.hoursToWrite.toSet(),
        )
        assertEquals(1, plan.summary.hoursAdded)
        assertEquals(1, plan.summary.hoursRaised)
        assertEquals(1, plan.summary.hoursUnchanged)
        assertEquals(250L, plan.summary.stepsGained) // 240 raised + 10 added
    }

    @Test
    fun `importing the same file twice changes nothing the second time`() {
        val afterFirst = sample.hours.associate { BucketKey(it.date.toEpochDay(), it.hour) to it.steps }
        val plan = BackupMerge.plan(afterFirst, sample.goals.map { it.from }.toSet(), sample)
        assertTrue(plan.hoursToWrite.isEmpty())
        assertTrue(plan.goalsToWrite.isEmpty())
        assertEquals(3, plan.summary.hoursUnchanged)
        assertEquals(0L, plan.summary.stepsGained)
    }

    @Test
    fun `an existing goal entry for a day is kept`() {
        val backup = BackupData(emptyList(), listOf(GoalRecord(d("2026-11-20"), 8_000), GoalRecord(d("2026-11-25"), 9_000)))
        val plan = BackupMerge.plan(emptyMap(), setOf(d("2026-11-20")), backup)
        assertEquals(listOf(GoalRecord(d("2026-11-25"), 9_000)), plan.goalsToWrite)
        assertEquals(1, plan.summary.goalsAdded)
    }

    @Test
    fun `an hour listed twice in a file counts once with the larger value`() {
        val backup = BackupData(
            listOf(HourRecord(d("2026-11-18"), 12, 100), HourRecord(d("2026-11-18"), 12, 300)),
            emptyList(),
        )
        val plan = BackupMerge.plan(emptyMap(), emptySet(), backup)
        assertEquals(listOf(HourRecord(d("2026-11-18"), 12, 300)), plan.hoursToWrite)
        assertEquals(1, plan.summary.hoursInFile)
        assertEquals(300L, plan.summary.stepsGained)
    }

    @Test
    fun `an empty file has no day range`() {
        val plan = BackupMerge.plan(emptyMap(), emptySet(), BackupData(emptyList(), emptyList()))
        assertNull(plan.summary.firstDay)
        assertNull(plan.summary.lastDay)
        assertEquals(0, plan.summary.hoursInFile)
    }
}
