package si.marko.step.core

object Goal {
    /** Daily step goal until it becomes configurable in settings (stage 5). */
    const val DEFAULT = 10_000

    /** Whole percent of the goal reached, rounded down. Goes above 100 on a good day. */
    fun percent(steps: Long, goal: Int): Int = if (goal <= 0) 0 else (steps * 100 / goal).toInt()

    fun reached(steps: Long, goal: Int): Boolean = goal > 0 && steps >= goal
}
