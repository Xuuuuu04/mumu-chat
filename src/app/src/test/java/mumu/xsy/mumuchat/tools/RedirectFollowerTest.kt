package mumu.xsy.mumuchat.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedirectFollowerTest {
    @Test
    fun tooManyRedirects() {
        val r = RedirectFollower.follow(
            startUrl = "https://example.com/a",
            maxRedirects = 2,
            fetch = { _ -> RedirectFollower.FetchResult(statusCode = 302, location = "/next") },
            resolve = { base, loc -> base + loc }
        )
        assertFalse(r.ok)
        assertEquals("too_many_redirects", r.code)
    }

    @Test
    fun redirectWithoutLocation() {
        val r = RedirectFollower.follow(
            startUrl = "https://example.com/a",
            maxRedirects = 1,
            fetch = { _ -> RedirectFollower.FetchResult(statusCode = 302, location = null) },
            resolve = { _, _ -> null }
        )
        assertFalse(r.ok)
        assertEquals("redirect_no_location", r.code)
    }

    @Test
    fun successfulFollow() {
        var calls = 0
        val r = RedirectFollower.follow(
            startUrl = "https://example.com/a",
            maxRedirects = 3,
            fetch = { _ ->
                calls += 1
                if (calls == 1) RedirectFollower.FetchResult(statusCode = 302, location = "/b")
                else RedirectFollower.FetchResult(statusCode = 200, body = "<html/>")
            },
            resolve = { base, loc -> base.substringBeforeLast("/") + loc }
        )
        assertTrue(r.ok)
        assertEquals("<html/>", r.body)
    }
}
