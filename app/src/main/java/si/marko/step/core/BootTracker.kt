package si.marko.step.core

import kotlin.math.abs

/**
 * What identifies "this boot" of the phone.
 *
 * @param bootCount Android's own counter of boots, when the system exposes it.
 * @param bootTimeMillis wall clock time of the boot, computed as "now minus time since boot".
 */
data class BootInfo(val bootCount: Int?, val bootTimeMillis: Long)

object BootTracker {

    /** Slack for the computed boot time, which jitters slightly between measurements. */
    const val BOOT_TIME_TOLERANCE_MS = 10_000L

    /**
     * True when the phone has been rebooted between [previous] and [current].
     *
     * The boot count is trusted when both sides have it. The computed boot time is only a
     * fallback, because changing the clock moves it without a reboot.
     */
    fun rebootedSince(previous: BootInfo?, current: BootInfo): Boolean {
        if (previous == null) return false
        if (previous.bootCount != null && current.bootCount != null) {
            return previous.bootCount != current.bootCount
        }
        return abs(current.bootTimeMillis - previous.bootTimeMillis) > BOOT_TIME_TOLERANCE_MS
    }
}
