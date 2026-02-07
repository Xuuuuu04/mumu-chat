package mumu.xsy.mumuchat.tools

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.util.Locale

object BrowseSafety {
    data class Result(
        val ok: Boolean,
        val code: String? = null,
        val message: String? = null
    )

    interface HostResolver {
        fun resolveAll(host: String): List<InetAddress>
    }

    object DefaultResolver : HostResolver {
        override fun resolveAll(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()
    }

    fun validate(
        url: String,
        allowlist: List<String>,
        denylist: List<String>,
        resolver: HostResolver = DefaultResolver
    ): Result {
        val httpUrl = url.trim().toHttpUrlOrNull() ?: return Result(false, "invalid_url", "无效的 URL")
        val scheme = httpUrl.scheme.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return Result(false, "unsupported_scheme", "仅支持 http/https")
        val host = httpUrl.host.trim()
        if (host.isBlank()) return Result(false, "invalid_host", "无效的 URL host")
        val hostLower = host.lowercase(Locale.ROOT)
        if (hostLower == "localhost") return Result(false, "ssrf_blocked", "禁止访问本机地址")
        if (hostLower.endsWith(".local")) return Result(false, "ssrf_blocked", "禁止访问本地域名")

        val deny = denylist.asSequence()
            .map { it.trim().trimStart('.').lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .toList()
        if (deny.any { d -> hostLower == d || hostLower.endsWith(".$d") }) {
            return Result(false, "denylist_blocked", "命中浏览 denylist")
        }

        val allow = allowlist.asSequence()
            .map { it.trim().trimStart('.').lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .toList()
        if (allow.isNotEmpty() && allow.none { a -> hostLower == a || hostLower.endsWith(".$a") }) {
            return Result(false, "allowlist_blocked", "不在浏览 allowlist")
        }

        val addrs = try {
            if (isIpLiteral(host)) listOf(InetAddress.getByName(stripIpv6Brackets(host))) else resolver.resolveAll(host)
        } catch (_: Exception) {
            return Result(false, "dns_failed", "无法解析 host")
        }
        val blocked = addrs.any { addr ->
            addr.isAnyLocalAddress ||
                addr.isLoopbackAddress ||
                addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress ||
                addr.isMulticastAddress
        }
        if (blocked) return Result(false, "ssrf_blocked", "禁止访问内网/本机地址")
        return Result(true)
    }

    private fun isIpLiteral(host: String): Boolean {
        val h = host.trim()
        if (h.startsWith("[") && h.endsWith("]")) return true
        if (h.contains(":")) return true
        return h.count { it == '.' } == 3 && h.all { it.isDigit() || it == '.' }
    }

    private fun stripIpv6Brackets(host: String): String {
        val h = host.trim()
        return if (h.startsWith("[") && h.endsWith("]") && h.length > 2) h.substring(1, h.length - 1) else h
    }
}
