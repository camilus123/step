package si.marko.step.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneId

class EventLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fixedNow = { Instant.parse("2026-11-17T21:40:03Z") }
    private val ljubljana = { ZoneId.of("Europe/Ljubljana") }

    private fun log(file: File, capacity: Int = 200) = EventLog(file, capacity, fixedNow, ljubljana)

    @Test
    fun `entries get a local timestamp with offset`() {
        val log = log(tmp.newFile())
        log.add("App started")
        assertEquals(listOf("2026-11-17 22:40:03 +01:00  App started"), log.snapshot())
    }

    @Test
    fun `only the newest entries are kept`() {
        val log = log(tmp.newFile(), capacity = 3)
        (1..5).forEach { log.add("event $it") }
        assertEquals(listOf("event 3", "event 4", "event 5"), log.snapshot().map { it.substringAfter("  ") })
    }

    @Test
    fun `entries survive a restart`() {
        val file = tmp.newFile()
        log(file).apply { add("one"); add("two") }
        val reopened = log(file)
        reopened.add("three")
        assertEquals(listOf("one", "two", "three"), reopened.snapshot().map { it.substringAfter("  ") })
    }

    @Test
    fun `line breaks cannot split an entry`() {
        val log = log(tmp.newFile())
        log.add("first\nsecond")
        assertEquals(1, log.snapshot().size)
        assertTrue(log.snapshot().single().endsWith("first second"))
    }
}
