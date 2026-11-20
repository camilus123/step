package si.marko.step.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Steps walked in one hour of one local calendar day. [epochDay] is LocalDate.toEpochDay(). */
@Entity(tableName = "hourly_steps", primaryKeys = ["epochDay", "hour"])
data class HourlySteps(val epochDay: Long, val hour: Int, val steps: Long)

/** Single row (id 0): the last accounted sensor reading. See core.CounterState. */
@Entity(tableName = "counter_state")
data class CounterStateRow(
    @PrimaryKey val id: Int = 0,
    val lastRaw: Long,
    val lastEventWallMillis: Long,
    val bootCount: Int?,
    val bootTimeMillis: Long,
)

/**
 * The daily goal that applies from [fromEpochDay] onward, until the next entry. Keeps past days
 * judged against the goal of their time. Unused until the goal becomes configurable (stage 5),
 * but part of the schema from the start so no migration is needed then.
 */
@Entity(tableName = "goal_history")
data class GoalChange(@PrimaryKey val fromEpochDay: Long, val goal: Int)

data class DayTotal(val epochDay: Long, val steps: Long)

data class HourTotal(val hour: Int, val steps: Long)

@Dao
abstract class StepDao {

    @Query("SELECT * FROM counter_state WHERE id = 0")
    abstract suspend fun counterState(): CounterStateRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun saveCounterState(row: CounterStateRow)

    // Two statements instead of an upsert: Android 10 ships an SQLite without "ON CONFLICT DO UPDATE".
    @Query("INSERT OR IGNORE INTO hourly_steps (epochDay, hour, steps) VALUES (:epochDay, :hour, 0)")
    protected abstract suspend fun ensureBucket(epochDay: Long, hour: Int)

    @Query("UPDATE hourly_steps SET steps = steps + :steps WHERE epochDay = :epochDay AND hour = :hour")
    protected abstract suspend fun addToBucket(epochDay: Long, hour: Int, steps: Long)

    /** Adds the credited steps and stores the new counter state together, or not at all. */
    @Transaction
    open suspend fun apply(credits: List<HourlySteps>, state: CounterStateRow) {
        for (credit in credits) {
            ensureBucket(credit.epochDay, credit.hour)
            addToBucket(credit.epochDay, credit.hour, credit.steps)
        }
        saveCounterState(state)
    }

    @Query("SELECT COALESCE(SUM(steps), 0) FROM hourly_steps WHERE epochDay = :epochDay")
    abstract fun dayTotal(epochDay: Long): Flow<Long>

    @Query("SELECT COALESCE(SUM(steps), 0) FROM hourly_steps WHERE epochDay = :epochDay")
    abstract suspend fun dayTotalOnce(epochDay: Long): Long

    @Query("SELECT COALESCE(SUM(steps), 0) FROM hourly_steps")
    abstract suspend fun allTimeTotal(): Long

    /** Totals of the days in the range that have any rows. Days without rows are simply absent. */
    @Query("SELECT epochDay, SUM(steps) AS steps FROM hourly_steps WHERE epochDay BETWEEN :fromEpochDay AND :toEpochDay GROUP BY epochDay")
    abstract fun dayTotals(fromEpochDay: Long, toEpochDay: Long): Flow<List<DayTotal>>

    @Query("SELECT hour, steps FROM hourly_steps WHERE epochDay = :epochDay")
    abstract fun hoursOfDay(epochDay: Long): Flow<List<HourTotal>>

    @Query("SELECT MIN(epochDay) FROM hourly_steps WHERE steps > 0")
    abstract suspend fun firstDayWithSteps(): Long?

    @Query("SELECT * FROM goal_history")
    abstract suspend fun goalChanges(): List<GoalChange>

    @Query("SELECT * FROM goal_history")
    abstract fun goalChangesFlow(): Flow<List<GoalChange>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveGoalChange(change: GoalChange)

    @Query("SELECT * FROM hourly_steps ORDER BY epochDay, hour")
    abstract suspend fun allHours(): List<HourlySteps>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun replaceHours(rows: List<HourlySteps>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun addGoalChanges(rows: List<GoalChange>)

    /** Writes the result of an import: all of it, or nothing. */
    @Transaction
    open suspend fun applyImport(hours: List<HourlySteps>, goals: List<GoalChange>) {
        replaceHours(hours)
        addGoalChanges(goals)
    }

    @Query("SELECT COUNT(DISTINCT epochDay) FROM hourly_steps WHERE steps > 0")
    abstract suspend fun daysWithSteps(): Int
}

@Database(
    entities = [HourlySteps::class, CounterStateRow::class, GoalChange::class],
    version = 1,
    exportSchema = false,
)
abstract class StepDatabase : RoomDatabase() {
    abstract fun stepDao(): StepDao

    companion object {
        fun open(context: Context): StepDatabase =
            Room.databaseBuilder(context.applicationContext, StepDatabase::class.java, "steps.db").build()
    }
}
