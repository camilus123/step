package si.marko.step.service

import android.content.SharedPreferences

/**
 * How the step notification tries to get onto the lock screen. HyperOS decides by its own
 * rules which notifications it shows there, and those rules are not documented, so these are
 * switchable from the app until it is known what the phone needs.
 *
 * @param normalImportance post on a channel of normal importance (still without sound or
 *        vibration) instead of the "silent" one. Some lock screens hide silent notifications.
 * @param repostOnScreenOff post the notification afresh each time the screen turns off. Some
 *        lock screens only list notifications that arrived since the last unlock.
 * @param liveUpdate ask Android 16 to treat it as a "live update", which gets a prominent
 *        place on the lock screen and a chip in the status bar.
 */
data class LockScreenOptions(
    val normalImportance: Boolean,
    val repostOnScreenOff: Boolean,
    val liveUpdate: Boolean,
) {
    fun save(prefs: SharedPreferences) {
        prefs.edit()
            .putBoolean(KEY_NORMAL_IMPORTANCE, normalImportance)
            .putBoolean(KEY_REPOST, repostOnScreenOff)
            .putBoolean(KEY_LIVE_UPDATE, liveUpdate)
            .apply()
    }

    companion object {
        const val KEY_NORMAL_IMPORTANCE = "lock_normal_importance"
        const val KEY_REPOST = "lock_repost_on_screen_off"
        const val KEY_LIVE_UPDATE = "lock_live_update"
        val KEYS = setOf(KEY_NORMAL_IMPORTANCE, KEY_REPOST, KEY_LIVE_UPDATE)

        fun load(prefs: SharedPreferences) = LockScreenOptions(
            normalImportance = prefs.getBoolean(KEY_NORMAL_IMPORTANCE, true),
            repostOnScreenOff = prefs.getBoolean(KEY_REPOST, true),
            liveUpdate = prefs.getBoolean(KEY_LIVE_UPDATE, false),
        )
    }
}
