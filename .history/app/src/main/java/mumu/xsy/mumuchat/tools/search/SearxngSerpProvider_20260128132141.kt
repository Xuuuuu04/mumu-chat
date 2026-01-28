package mumu.xsy.mumuchat.tools.search

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import mumu.xsy.mumuchat.tools.RemoteApiClient
import mumu.xsy.mumuchat.tools.RemoteEndpoint
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class SearxngSerpProvider(
    private val remoteApiClient: RemoteApiClient,
    private val baseUrl: String
) : SerpProvider {
    override val id: String = "searxng"

    override suspend fun search(query: String, limit: Int, page: Int): SerpResponse {
        val rn = limit.coerceIn(1, 10)
        val p = page.coerceAtLeast(1)
        val base = baseUrl.trim().trimEnd('/')
        if (base.isBlank()) throw SerpProviderException("invalid_config", "未配置 SearXNG 地址")

        val url = (base + "/search").toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("q", query)
            ?.addQueryParameter("format", "json")
            ?.addQueryParameter("pageno", p.toString())
            ?.addQueryParameter("language", "zh-CN")
            ?.addQueryParameter("safesearch", "0")
            ?.build()
            ?.toString()
            ?: throw SerpProviderException("invalid_config", "SearXNG 地址无效")

        val endpoint = RemoteEndpoint(
            id = "serp_searxng",
            url = url,
            ttlMs = 2 * 60_000L,
            timeoutMs = 8_000L,
            maxBytes = 1024 * 1024L,
            maxPerMinute = 30,
            followRedirects = false
        )
        val cacheKey = "${endpoint.id}|${base}|$rn|$p|${query.trim()}"
        val r = remoteApiClient.fetch(endpoint, bypassCache = false, cacheKey = cacheKey)
        if (!r.ok) throw SerpProviderException(r.code ?: "fetch_failed", r.message ?: "请求失败")

        val json = r.body.orEmpty()
        val root = runCatching { JsonParser.parseString(json) }.getOrNull()
            ?: throw SerpProviderException("parse_failed", "返回不是有效 JSON")
        if (!root.isJsonObject) throw SerpProviderException("parse_failed", "返回不是 JSON 对象")

        val results = root.asJsonObject.getAsJsonArray("results")
            ?: throw SerpProviderException("parse_failed", "缺少 results 字段")

        val items = results.mapIndexedNotNull { idx, el ->
            if (idx >= rn) return@mapIndexedNotNull null
            val obj: JsonObject = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapIndexedNotNull null
            val title = obj.get("title")?.asString?.trim().orEmpty()
            val urlStr = obj.get("url")?.asString?.trim().orEmpty()
            if (title.isBlank() || urlStr.isBlank()) return@mapIndexedNotNull null
            val snippet = obj.get("content")?.asString?.trim()?.takeIf { it.isNotBlank() }
            SerpItem(
                rank = idx + 1,
                title = title,
                url = urlStr,
                displayUrl = null,
                snippet = snippet,
                engine = id
            )
        }

        return SerpResponse(
            engine = id,
            results = items,
            latencyMs = r.latencyMs,
            cached = (r.code == "cached")
        )
    }
}
