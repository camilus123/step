package si.marko.step.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.LocalDate

/** Steps of one hour (0 to 23) of one local calendar day. */
data class HourRecord(val date: LocalDate, val hour: Int, val steps: Long)

/** "From this day on the daily goal is this." */
data class GoalRecord(val from: LocalDate, val goal: Int)

/** Everything worth carrying to another phone: the hourly history and the goal history. */
data class BackupData(val hours: List<HourRecord>, val goals: List<GoalRecord>)

class BackupFormatException(message: String) : Exception(message)

/**
 * The backup file. Pure Kotlin, unit tested.
 *
 * JSON, one object:
 *
 *     {
 *       "format": "step-backup",
 *       "formatVersion": 1,
 *       "exportedAt": "2026-11-20T21:50:00+01:00",   (informational)
 *       "appVersion": "0.5.11",                      (informational)
 *       "hours": [ { "date": "2026-11-18", "hour": 12, "steps": 340 }, ... ],
 *       "goals": [ { "from": "2026-11-20", "goal": 8000 }, ... ]
 *     }
 *
 * Dates are local calendar dates, hours are 0 to 23 in local time, exactly as stored. The app's
 * position in the sensor's count is not part of a backup: it belongs to one phone and one boot.
 *
 * A reader accepts any file whose formatVersion is not higher than [FORMAT_VERSION] and ignores
 * fields it does not know, so later versions can add fields without breaking older files.
 */
object BackupFormat {
    const val FORMAT_NAME = "step-backup"
    const val FORMAT_VERSION = 1

    /** Far above anything walkable in an hour; only there to reject nonsense. */
    private const val MAX_STEPS_PER_HOUR = 100_000L
    private const val MAX_GOAL = 1_000_000

    fun toJson(data: BackupData, exportedAt: String, appVersion: String): String {
        val root = buildJsonObject {
            put("format", FORMAT_NAME)
            put("formatVersion", FORMAT_VERSION)
            put("exportedAt", exportedAt)
            put("appVersion", appVersion)
            put("hours", buildJsonArray {
                data.hours.sortedWith(compareBy({ it.date }, { it.hour })).forEach { record ->
                    add(buildJsonObject {
                        put("date", record.date.toString())
                        put("hour", record.hour)
                        put("steps", record.steps)
                    })
                }
            })
            put("goals", buildJsonArray {
                data.goals.sortedBy { it.from }.forEach { record ->
                    add(buildJsonObject {
                        put("from", record.from.toString())
                        put("goal", record.goal)
                    })
                }
            })
        }
        return root.toString()
    }

    /** @throws BackupFormatException with a message fit to show, if the text is not a usable backup. */
    fun fromJson(text: String): BackupData {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw BackupFormatException("This is not a Step backup file (not valid JSON).")

        if (root.string("format") != FORMAT_NAME) {
            throw BackupFormatException("This is not a Step backup file.")
        }
        val version = (root["formatVersion"] as? JsonPrimitive)?.intOrNull
            ?: throw BackupFormatException("The backup file has no format version.")
        if (version > FORMAT_VERSION) {
            throw BackupFormatException("The backup was made by a newer version of the app (format $version). Update the app first.")
        }

        val hours = root.array("hours").mapIndexed { index, element ->
            val entry = element as? JsonObject ?: throw BackupFormatException("Hour entry ${index + 1} is malformed.")
            val date = entry.date("date") ?: throw BackupFormatException("Hour entry ${index + 1} has no valid date.")
            val hour = (entry["hour"] as? JsonPrimitive)?.intOrNull
            val steps = (entry["steps"] as? JsonPrimitive)?.longOrNull
            if (hour == null || hour !in 0..23) throw BackupFormatException("Hour entry ${index + 1} ($date) has no valid hour.")
            if (steps == null || steps !in 0..MAX_STEPS_PER_HOUR) {
                throw BackupFormatException("Hour entry ${index + 1} ($date, hour $hour) has no valid step count.")
            }
            HourRecord(date, hour, steps)
        }
        val goals = root.array("goals").mapIndexed { index, element ->
            val entry = element as? JsonObject ?: throw BackupFormatException("Goal entry ${index + 1} is malformed.")
            val from = entry.date("from") ?: throw BackupFormatException("Goal entry ${index + 1} has no valid date.")
            val goal = (entry["goal"] as? JsonPrimitive)?.intOrNull
            if (goal == null || goal !in 1..MAX_GOAL) throw BackupFormatException("Goal entry ${index + 1} ($from) has no valid goal.")
            GoalRecord(from, goal)
        }
        return BackupData(hours, goals)
    }

    /** The hourly history as a table for a spreadsheet: date, hour, steps. Export only. */
    fun toCsv(data: BackupData): String = buildString {
        append("date,hour,steps\n")
        data.hours.sortedWith(compareBy({ it.date }, { it.hour })).forEach {
            append(it.date).append(',').append(it.hour).append(',').append(it.steps).append('\n')
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.date(key: String): LocalDate? = string(key)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** A missing list is an empty list; anything else that is not a list is an error. */
    private fun JsonObject.array(key: String): List<JsonElement> = when (val value = this[key]) {
        null -> emptyList()
        is JsonArray -> value
        else -> throw BackupFormatException("The \"$key\" part of the backup file is malformed.")
    }
}

/** What an import did, for telling the user. */
data class ImportSummary(
    val hoursInFile: Int,
    /** Hours that were not in the app before. */
    val hoursAdded: Int,
    /** Hours where the file had more steps than the app, so the app's value was raised. */
    val hoursRaised: Int,
    /** Hours where the app already had as many steps or more; left alone. */
    val hoursUnchanged: Int,
    /** By how many steps the app's history grew in total. */
    val stepsGained: Long,
    val goalsAdded: Int,
    val firstDay: LocalDate?,
    val lastDay: LocalDate?,
)

data class ImportPlan(val hoursToWrite: List<HourRecord>, val goalsToWrite: List<GoalRecord>, val summary: ImportSummary)

/**
 * Decides what an import changes. Pure Kotlin, unit tested.
 *
 * - For every hour, the larger of the two values wins. Importing can therefore never lose steps,
 *   importing the same file twice changes nothing the second time, and a backup made earlier
 *   today does not overwrite the steps walked since.
 * - A goal entry from the file is added when the app has no entry for that day; where both have
 *   one, the app's own stays.
 */
object BackupMerge {

    fun plan(existingHours: Map<BucketKey, Long>, existingGoalDays: Set<LocalDate>, backup: BackupData): ImportPlan {
        // Should a file list an hour twice, treat it like the rule above: the larger value.
        val imported = LinkedHashMap<Pair<LocalDate, Int>, Long>()
        backup.hours.forEach { record -> imported.merge(record.date to record.hour, record.steps, ::maxOf) }

        val toWrite = mutableListOf<HourRecord>()
        var added = 0
        var raised = 0
        var gained = 0L
        for ((key, steps) in imported) {
            val (date, hour) = key
            val current = existingHours[BucketKey(date.toEpochDay(), hour)]
            when {
                current == null -> {
                    added++
                    gained += steps
                    toWrite += HourRecord(date, hour, steps)
                }
                steps > current -> {
                    raised++
                    gained += steps - current
                    toWrite += HourRecord(date, hour, steps)
                }
            }
        }
        val goals = backup.goals.distinctBy { it.from }.filter { it.from !in existingGoalDays }
        val days = imported.keys.map { it.first }
        return ImportPlan(
            hoursToWrite = toWrite,
            goalsToWrite = goals,
            summary = ImportSummary(
                hoursInFile = imported.size,
                hoursAdded = added,
                hoursRaised = raised,
                hoursUnchanged = imported.size - added - raised,
                stepsGained = gained,
                goalsAdded = goals.size,
                firstDay = days.minOrNull(),
                lastDay = days.maxOrNull(),
            ),
        )
    }
}
