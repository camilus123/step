package si.marko.step.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalTest {
    @Test
    fun `percent rounds down and may exceed 100`() {
        assertEquals(0, Goal.percent(0, 10_000))
        assertEquals(64, Goal.percent(6_482, 10_000))
        assertEquals(99, Goal.percent(9_999, 10_000))
        assertEquals(100, Goal.percent(10_000, 10_000))
        assertEquals(123, Goal.percent(12_345, 10_000))
    }

    @Test
    fun `typed goals are accepted only as whole numbers in range`() {
        assertEquals(8_000, Goal.parse("8000"))
        assertEquals(8_000, Goal.parse(" 8000 "))
        assertEquals(Goal.MIN, Goal.parse(Goal.MIN.toString()))
        assertEquals(Goal.MAX, Goal.parse(Goal.MAX.toString()))
        assertNull(Goal.parse(""))
        assertNull(Goal.parse("8 000"))
        assertNull(Goal.parse("8000.5"))
        assertNull(Goal.parse("-5"))
        assertNull(Goal.parse((Goal.MIN - 1).toString()))
        assertNull(Goal.parse((Goal.MAX + 1).toString()))
        assertNull(Goal.parse("99999999999999"))
    }

    @Test
    fun `reached exactly at the goal`() {
        assertFalse(Goal.reached(9_999, 10_000))
        assertTrue(Goal.reached(10_000, 10_000))
        assertFalse(Goal.reached(5, 0))
    }
}
