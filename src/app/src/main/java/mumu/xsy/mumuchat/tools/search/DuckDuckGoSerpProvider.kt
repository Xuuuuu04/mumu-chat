package mumu.xsy.mumuchat.tools.search

import mumu.xsy.mumuchat.tools.RemoteApiClient
import mumu.xsy.mumuchat.tools.RemoteEndpoint
import okhttp3.HttpUrl.Companion.toHttpUrl

class DuckDuckGoSerpProvider(
    private val remoteApiClient: RemoteApiClient
) : SerpProvider {
    override val id: String = "duckduckgo"

    override suspend fun search(query: String, limit: Int, page: Int): SerpResponse {
        val rn = limit.coerceIn(1, 10)
        val p = page.coerceAtLeast(1)
        val url = "https://html.duckduckgo.com/html/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("s", ((p - 1) * rn).toString())
            .build()
            .toString()

        val endpoint = RemoteEndpoint(
            id = "serp_duckduckgo",
            url = url,
            ttlMs = 5 * 60_000L,
            timeoutMs = 8_000L,
            maxBytes = 512 * 1024L,
            maxPerMinute = 15,
            followRedirects = false
        )
        val cacheKey = "${endpoint.id}|$rn|$p|${query.trim()}"
        val r = remoteApiClient.fetch(endpoint, bypassCache = false, cacheKey = cacheKey)
        if (!r.ok) throw SerpProviderException(r.code ?: "fetch_failed", r.message ?: "请求失败")

        val html = r.body.orEmpty()
        val items = DuckDuckGoSerpParser.parse(html, rn)
        return SerpResponse(
            engine = id,
            results = items,
            latencyMs = r.latencyMs,
            cached = (r.code == "cached")
        )
    }
}

