package mumu.xsy.mumuchat.tools

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

data class RemoteEndpoint(
    val id: String,
    val url: String,
    val ttlMs: Long,
    val timeoutMs: Long,
    val maxBytes: Long,
    val maxPerMinute: Int,
    val followRedirects: Boolean = false
) {
    fun host(): String = url.toHttpUrl().host.lowercase(Locale.ROOT)
}

object PublicApiRegistry {
    val endpoints: List<RemoteEndpoint> = listOf(
        RemoteEndpoint(
            id = "60s_viki_json",
            url = "https://60s.viki.moe/v2/60s",
            ttlMs = 10 * 60_000L,
            timeoutMs = 6_000L,
            maxBytes = 512 * 1024L,
            maxPerMinute = 30
        ),
        RemoteEndpoint(
            id = "60s_viki_text",
            url = "https://60s.viki.moe/v2/60s?encoding=text",
            ttlMs = 10 * 60_000L,
            timeoutMs = 6_000L,
            maxBytes = 512 * 1024L,
            maxPerMinute = 30
        ),
        RemoteEndpoint(
            id = "60s_viki_image",
            url = "https://60s.viki.moe/v2/60s?encoding=image",
            ttlMs = 30 * 60_000L,
            timeoutMs = 6_000L,
            maxBytes = 64 * 1024L,
            maxPerMinute = 20,
            followRedirects = true
        ),
        RemoteEndpoint(
            id = "60s_114128_json",
            url = "https://60s-api.114128.xyz/v2/60s",
            ttlMs = 10 * 60_000L,
            timeoutMs = 6_000L,
            maxBytes = 512 * 1024L,
            maxPerMinute = 30
        ),
        RemoteEndpoint(
            id = "tenapi_weibohot",
            url = "https://tenapi.cn/v2/weibohot",
            ttlMs = 60_000L,
            timeoutMs = 6_000L,
            maxBytes = 512 * 1024L,
            maxPerMinute = 60
        ),
        RemoteEndpoint(
            id = "tenapi_zhihuhot",
            url = "https://tenapi.cn/v2/zhihuhot",
            ttlMs = 60_000L,
            timeoutMs = 6_000L,
            maxBytes = 512 * 1024L,
            maxPerMinute = 60
        ),
        RemoteEndpoint(
            id = "tenapi_douyinhot",
            url = "https://tenapi.cn/v2/douyinhot",
            ttlMs = 60_000L,
            timeoutMs = 6_000L,
            maxBytes = 512 * 1024L,
            maxPerMinute = 60
        ),
        RemoteEndpoint(
            id = "tenapi_baiduhot",
            url = "https://tenapi.cn/v2/baiduhot",
            ttlMs = 60_000L,
            timeoutMs = 6_000L,
            maxBytes = 512 * 1024L,
            maxPerMinute = 60
        ),
        RemoteEndpoint(
            id = "tenapi_toutiaohot",
            url = "https://tenapi.cn/v2/toutiaohot",
            ttlMs = 60_000L,
            timeoutMs = 6_000L,
            maxBytes = 512 * 1024L,
            maxPerMinute = 60
        ),
        RemoteEndpoint(
            id = "tenapi_bilibilihot",
            url = "https://tenapi.cn/v2/bilibilihot",
            ttlMs = 60_000L,
            timeoutMs = 6_000L,
            maxBytes = 512 * 1024L,
            maxPerMinute = 60
        ),
        RemoteEndpoint(
            id = "tenapi_yiyan",
            url = "https://tenapi.cn/v2/yiyan",
            ttlMs = 10 * 60_000L,
            timeoutMs = 6_000L,
            maxBytes = 32 * 1024L,
            maxPerMinute = 60
        )
    )

    fun find(id: String): RemoteEndpoint? = endpoints.firstOrNull { it.id == id }

    fun isKnownUrl(url: String): Boolean {
        val u = url.trim().toHttpUrlOrNull() ?: return false
        return endpoints.any { it.url.toHttpUrlOrNull()?.host == u.host }
    }
}
