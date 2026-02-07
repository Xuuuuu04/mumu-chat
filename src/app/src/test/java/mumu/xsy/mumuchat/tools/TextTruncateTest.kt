package mumu.xsy.mumuchat.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTruncateTest {
    @Test
    fun noTruncateWhenShort() {
        val r = TextTruncate.limit("abc", 5)
        assertFalse(r.truncated)
        assertEquals(3, r.length)
        assertEquals("abc", r.text)
    }

    @Test
    fun truncatesWhenLong() {
        val r = TextTruncate.limit("abcdef", 3)
        assertTrue(r.truncated)
        assertEquals(6, r.length)
        assertEquals("abc", r.text)
    }
}
