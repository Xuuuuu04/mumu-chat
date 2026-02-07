package mumu.xsy.mumuchat.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class BrowseSafetyTest {
    @Test
    fun denylistHasPriorityOverAllowlist() {
        val r = BrowseSafety.validate(
            url = "https://example.com/a",
            allowlist = listOf("example.com"),
            denylist = listOf("example.com"),
            resolver = object : BrowseSafety.HostResolver {
                override fun resolveAll(host: String): List<InetAddress> = listOf(InetAddress.getByName("93.184.216.34"))
            }
        )
        assertFalse(r.ok)
        assertEquals("denylist_blocked", r.code)
    }

    @Test
    fun allowlistBlocksNonMatchingDomain() {
        val r = BrowseSafety.validate(
            url = "https://other.com/a",
            allowlist = listOf("example.com"),
            denylist = emptyList(),
            resolver = object : BrowseSafety.HostResolver {
                override fun resolveAll(host: String): List<InetAddress> = listOf(InetAddress.getByName("93.184.216.34"))
            }
        )
        assertFalse(r.ok)
        assertEquals("allowlist_blocked", r.code)
    }

    @Test
    fun subdomainMatchesRules() {
        val allowed = BrowseSafety.validate(
            url = "https://a.b.example.com/x",
            allowlist = listOf("example.com"),
            denylist = emptyList(),
            resolver = object : BrowseSafety.HostResolver {
                override fun resolveAll(host: String): List<InetAddress> = listOf(InetAddress.getByName("93.184.216.34"))
            }
        )
        assertTrue(allowed.ok)

        val denied = BrowseSafety.validate(
            url = "https://a.b.example.com/x",
            allowlist = listOf("example.com"),
            denylist = listOf("example.com"),
            resolver = object : BrowseSafety.HostResolver {
                override fun resolveAll(host: String): List<InetAddress> = listOf(InetAddress.getByName("93.184.216.34"))
            }
        )
        assertFalse(denied.ok)
        assertEquals("denylist_blocked", denied.code)
    }

    @Test
    fun ipv6LoopbackIsBlocked() {
        val r = BrowseSafety.validate(
            url = "http://[::1]/",
            allowlist = emptyList(),
            denylist = emptyList()
        )
        assertFalse(r.ok)
        assertEquals("ssrf_blocked", r.code)
    }

    @Test
    fun privateIpv4IsBlocked() {
        val r = BrowseSafety.validate(
            url = "http://192.168.0.2/",
            allowlist = emptyList(),
            denylist = emptyList()
        )
        assertFalse(r.ok)
        assertEquals("ssrf_blocked", r.code)
    }

    @Test
    fun dnsFailureReturnsDnsFailed() {
        val r = BrowseSafety.validate(
            url = "https://example.com/",
            allowlist = emptyList(),
            denylist = emptyList(),
            resolver = object : BrowseSafety.HostResolver {
                override fun resolveAll(host: String): List<InetAddress> = throw RuntimeException("no dns")
            }
        )
        assertFalse(r.ok)
        assertEquals("dns_failed", r.code)
    }
}
