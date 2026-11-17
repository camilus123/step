package si.marko.step.diag

/** One titled block of "key: value" lines in the diagnostics. */
data class DiagSection(val title: String, val rows: List<Pair<String, String>>)

/**
 * Everything the app knows about its own state, in a form that can be shown on screen
 * and copied as plain text. Keys are deliberately always English, whatever the interface
 * language, because this text is meant to be pasted into a bug report.
 */
data class DiagnosticsReport(val sections: List<DiagSection>, val events: List<String>) {

    fun toText(): String = buildString {
        appendLine("Step diagnostics")
        for (section in sections) {
            appendLine()
            appendLine("[${section.title}]")
            for ((key, value) in section.rows) appendLine("$key: $value")
        }
        appendLine()
        appendLine("[Recent events]")
        if (events.isEmpty()) appendLine("(none)") else events.forEach { appendLine(it) }
    }.trimEnd()
}
