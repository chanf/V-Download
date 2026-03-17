package com.vdown.app.cookie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetscapeCookieParserTest {
    private val parser = NetscapeCookieParser()

    @Test
    fun `should parse valid netscape cookie lines including HttpOnly`() {
        val input = """
            # Netscape HTTP Cookie File
            .example.com	TRUE	/	FALSE	1893456000	sid	abc
            #HttpOnly_.secure.example.com	TRUE	/	TRUE	0	token	xyz
        """.trimIndent()

        val result = parser.parse(input.reader().buffered())

        assertEquals(2, result.cookies.size)
        assertEquals(1, result.skippedCommentLines)
        assertEquals(0, result.skippedInvalidLines)

        assertEquals(".example.com", result.cookies[0].domain)
        assertFalse(result.cookies[0].httpOnly)
        assertTrue(result.cookies[1].httpOnly)
        assertEquals(".secure.example.com", result.cookies[1].domain)
    }

    @Test
    fun `should skip invalid lines`() {
        val input = """
            .example.com	TRUE	/	FALSE	1893456000	sid
            .ok.com	FALSE	/	FALSE	1893456000	a	b
        """.trimIndent()

        val result = parser.parse(input.reader().buffered())

        assertEquals(1, result.cookies.size)
        assertEquals(1, result.skippedInvalidLines)
    }
}
