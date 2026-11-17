package si.marko.step.diag

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsReportTest {

    @Test
    fun `text has sections then events`() {
        val report = DiagnosticsReport(
            sections = listOf(
                DiagSection("App", listOf("Version" to "0.1 (7)")),
                DiagSection("Step sensor", listOf("Step counter present" to "yes", "Step counter vendor" to "Acme")),
            ),
            events = listOf("2026-11-17 22:40:03 +01:00  App started"),
        )
        val expected = """
            Step diagnostics

            [App]
            Version: 0.1 (7)

            [Step sensor]
            Step counter present: yes
            Step counter vendor: Acme

            [Recent events]
            2026-11-17 22:40:03 +01:00  App started
        """.trimIndent()
        assertEquals(expected, report.toText().replace("\r\n", "\n"))
    }

    @Test
    fun `empty event list is stated explicitly`() {
        val text = DiagnosticsReport(emptyList(), emptyList()).toText()
        assertEquals("Step diagnostics\n\n[Recent events]\n(none)", text.replace("\r\n", "\n"))
    }
}
