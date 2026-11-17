package si.marko.step.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import si.marko.step.core.BootInfo
import si.marko.step.core.CounterState
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

    suspend fun counterState(): CounterStateRow? = dao.counterState()
    suspend fun dayTotalOnce(epochDay: Long): Long = dao.dayTotalOnce(epochDay)
    suspend fun allTimeTotal(): Long = dao.allTimeTotal()
    suspend fun daysWithSteps(): Int = dao.daysWithSteps()
}
