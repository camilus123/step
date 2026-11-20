package si.marko.step.core

object Goal {
    /** The daily step goal until the user sets another one. */
    const val DEFAULT = 10_000

    /** What the settings accept. Wide enough for anyone, narrow enough to catch a typo. */
    const val MIN = 100
    const val MAX = 200_000

    /** The goal a typed text stands for, or null when it is not a whole number in range. */
    fun parse(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in MIN..MAX }

    /** Whole percent of the goal reached, rounded down. Goes above 100 on a good day. */
    fun percent(steps: Long, goal: Int): Int = if (goal <= 0) 0 else (steps * 100 / goal).toInt()

    fun reached(steps: Long, goal: Int): Boolean = goal > 0 && steps >= goal
}
