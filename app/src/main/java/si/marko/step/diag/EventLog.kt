package si.marko.step.diag

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A short, persistent log of notable events (app starts, reboots detected, sensor oddities).
 * Keeps only the newest [capacity] lines. Plain JVM code, so it is unit tested.
 */
class EventLog(
    private val file: File,
    private val capacity: Int = 200,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val lines = ArrayDeque<String>()

    init {
        if (file.exists()) {
            runCatching { file.readLines().filter { it.isNotBlank() }.takeLast(capacity) }
                .getOrDefault(emptyList())
                .forEach(lines::addLast)
        }
    }

    @Synchronized
    fun add(message: String) {
        val stamp = TIME_FORMAT.format(now().atZone(zone()))
        lines.addLast("$stamp  ${message.replace('\n', ' ')}")
        while (lines.size > capacity) lines.removeFirst()
        // The file is tiny (at most [capacity] lines), so rewriting it whole is fine.
        runCatching { file.writeText(lines.joinToString("\n", postfix = "\n")) }
    }

    /** Oldest first. */
    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")
    }
}
