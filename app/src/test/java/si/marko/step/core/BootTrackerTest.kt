package si.marko.step.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootTrackerTest {

    @Test
    fun `first ever launch is not a reboot`() {
        assertFalse(BootTracker.rebootedSince(null, BootInfo(5, 1_000_000)))
    }

    @Test
    fun `same boot count means no reboot even if the clock moved`() {
        val before = BootInfo(5, 1_000_000)
        val after = BootInfo(5, 1_000_000 + 3_600_000) // user changed the time zone or clock
        assertFalse(BootTracker.rebootedSince(before, after))
    }

    @Test
    fun `changed boot count means reboot`() {
        assertTrue(BootTracker.rebootedSince(BootInfo(5, 1_000_000), BootInfo(6, 1_000_500)))
    }

    @Test
    fun `without boot count a small boot time jitter is not a reboot`() {
        assertFalse(BootTracker.rebootedSince(BootInfo(null, 1_000_000), BootInfo(null, 1_000_800)))
    }

    @Test
    fun `without boot count a different boot time is a reboot`() {
        assertTrue(BootTracker.rebootedSince(BootInfo(null, 1_000_000), BootInfo(null, 1_000_000 + 60_000)))
    }

    @Test
    fun `boot count on only one side falls back to boot time`() {
        assertFalse(BootTracker.rebootedSince(BootInfo(null, 1_000_000), BootInfo(7, 1_000_100)))
        assertTrue(BootTracker.rebootedSince(BootInfo(7, 1_000_000), BootInfo(null, 9_000_000)))
    }
}
