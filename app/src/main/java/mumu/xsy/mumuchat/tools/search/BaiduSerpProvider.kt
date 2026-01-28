package mumu.xsy.mumuchat.tools.search

import mumu.xsy.mumuchat.tools.RemoteApiClient
import mumu.xsy.mumuchat.tools.RemoteEndpoint
import okhttp3.HttpUrl.Companion.toHttpUrl

class BaiduSerpProvider(
    private val remoteApiClient: RemoteApiClient
) : SerpProvider {
    override val id: String = "baidu"

    override suspend fun search(query: String, limit: Int, page: Int): SerpResponse {
        val p = page.coerceAtLeast(1)
        val rn = limit.coerceIn(1, 10)
        val pn = (p - 1) * rn
        val url = "https://www.baidu.com/s".toHttpUrl().newBuilder()
            .addQueryParameter("wd", query)
            .addQueryParameter("rn", rn.toString())
            .addQueryParameter("pn", pn.toString())
            .build()
            .toString()

        val endpoint = RemoteEndpoint(
            id = "serp_baidu",
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
        if (BaiduSerpParser.looksLikeCaptcha(html)) throw SerpProviderException("captcha", "搜索引擎要求安全验证")
        val items = BaiduSerpParser.parse(html, rn)
        return SerpResponse(
            engine = id,
            results = items,
            latencyMs = r.latencyMs,
            cached = (r.code == "cached")
        )
    }
}
