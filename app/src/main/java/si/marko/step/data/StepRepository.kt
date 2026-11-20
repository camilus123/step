package si.marko.step.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import si.marko.step.core.BootInfo
import si.marko.step.core.BackupData
import si.marko.step.core.BackupMerge
import si.marko.step.core.BucketKey
import si.marko.step.core.CounterState
import si.marko.step.core.GoalRecord
import si.marko.step.core.HourRecord
import si.marko.step.core.ImportSummary
import si.marko.step.core.GoalHistory
import si.marko.step.core.StepAccountant
import si.marko.step.diag.EventLog
import si.marko.step.sensor.SensorReading
import java.time.LocalDate
import java.time.ZoneId

/** The one place where sensor readings become stored steps. */
class StepRepository(
    private val dao: StepDao,
    private val log: EventLog,
    private val onReboot: (Long) -> Unit,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    // Readings can arrive from the service and from the watchdog. One at a time, in order.
    private val mutex = Mutex()

    suspend fun record(reading: SensorReading, boot: BootInfo, source: String) = mutex.withLock {
        val previous = dao.counterState()?.let {
            CounterState(it.lastRaw, it.lastEventWallMillis, BootInfo(it.bootCount, it.bootTimeMillis))
        }
        val outcome = StepAccountant.process(previous, reading.rawSteps, reading.eventWallMillis, boot, zone())
        if (outcome.state != previous || outcome.credits.isNotEmpty()) {
            dao.apply(
                credits = outcome.credits.map { (key, steps) -> HourlySteps(key.epochDay, key.hour, steps) },
                state = CounterStateRow(
                    lastRaw = outcome.state.lastRaw,
                    lastEventWallMillis = outcome.state.lastEventWallMillis,
                    bootCount = outcome.state.boot.bootCount,
                    bootTimeMillis = outcome.state.boot.bootTimeMillis,
                ),
            )
        }
        if (outcome.rebooted) onReboot(reading.deliveredWallMillis)
        outcome.note?.let { log.add("$it [$source]") }
    }

    fun todayEpochDay(): Long = LocalDate.now(zone()).toEpochDay()

    fun dayTotal(epochDay: Long): Flow<Long> = dao.dayTotal(epochDay)

    /** Steps per day for the days of the range that have any; other days are absent. */
    fun dayTotals(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, Long>> =
        dao.dayTotals(from.toEpochDay(), to.toEpochDay()).map { rows ->
            rows.associate { LocalDate.ofEpochDay(it.epochDay) to it.steps }
        }

    /** Steps per hour (0 to 23) of one day, for the hours that have any. */
    fun hoursOfDay(epochDay: Long): Flow<Map<Int, Long>> =
        dao.hoursOfDay(epochDay).map { rows -> rows.associate { it.hour to it.steps } }

    suspend fun firstDayWithSteps(): LocalDate? = dao.firstDayWithSteps()?.let(LocalDate::ofEpochDay)

    suspend fun goalHistory(): GoalHistory = dao.goalChanges().toHistory()

    fun goalHistoryFlow(): Flow<GoalHistory> = dao.goalChangesFlow().map { it.toHistory() }

    private fun List<GoalChange>.toHistory() =
        GoalHistory(associate { LocalDate.ofEpochDay(it.fromEpochDay) to it.goal })

    /** The goal that applies on the given day, following changes live. */
    fun goalOn(epochDay: Long): Flow<Int> = goalHistoryFlow().map { it.goalOn(LocalDate.ofEpochDay(epochDay)) }

    /** Sets the goal from today on. Earlier days keep the goal they had. */
    suspend fun setGoal(goal: Int) {
        dao.saveGoalChange(GoalChange(todayEpochDay(), goal))
        log.add("Goal set to $goal from ${LocalDate.ofEpochDay(todayEpochDay())} on")
    }

    suspend fun goalChangeCount(): Int = dao.goalChanges().size

    // ---- export and import -------------------------------------------------------------

    suspend fun exportData(): BackupData = BackupData(
        hours = dao.allHours().map { HourRecord(LocalDate.ofEpochDay(it.epochDay), it.hour, it.steps) },
        goals = dao.goalChanges().map { GoalRecord(LocalDate.ofEpochDay(it.fromEpochDay), it.goal) },
    )

    /** Merges a backup into the stored history (see [BackupMerge]) and says what changed. */
    suspend fun importData(backup: BackupData): ImportSummary = mutex.withLock {
        val plan = BackupMerge.plan(
            existingHours = dao.allHours().associate { BucketKey(it.epochDay, it.hour) to it.steps },
            existingGoalDays = dao.goalChanges().map { LocalDate.ofEpochDay(it.fromEpochDay) }.toSet(),
            backup = backup,
        )
        dao.applyImport(
            hours = plan.hoursToWrite.map { HourlySteps(it.date.toEpochDay(), it.hour, it.steps) },
            goals = plan.goalsToWrite.map { GoalChange(it.from.toEpochDay(), it.goal) },
        )
        val s = plan.summary
        log.add(
            "Import: ${s.hoursInFile} hours in file, ${s.hoursAdded} added, ${s.hoursRaised} raised, " +
                "${s.hoursUnchanged} unchanged, +${s.stepsGained} steps, ${s.goalsAdded} goal entries added"
        )
        s
    }

    suspend fun counterState(): CounterStateRow? = dao.counterState()
    suspend fun dayTotalOnce(epochDay: Long): Long = dao.dayTotalOnce(epochDay)
    suspend fun allTimeTotal(): Long = dao.allTimeTotal()
    suspend fun daysWithSteps(): Int = dao.daysWithSteps()
}
