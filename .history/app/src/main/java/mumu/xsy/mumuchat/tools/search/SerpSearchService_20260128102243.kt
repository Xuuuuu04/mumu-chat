package mumu.xsy.mumuchat.tools.search

class SerpSearchService(
    private val providersInOrder: List<SerpProvider>
) {
    private val providerMap: Map<String, SerpProvider> = providersInOrder.associateBy { it.id }

    suspend fun search(
        query: String,
        engine: String?,
        limit: Int,
        page: Int,
        site: String?
    ): SerpResponse {
        val q = buildQuery(query, site)
        val requested = normalizeEngine((engine ?: "auto").trim().lowercase())
        if (requested != "auto") {
            val provider = providerMap[requested] ?: throw SerpProviderException("invalid_engine", "不支持的搜索引擎: $requested")
            return provider.search(q, limit, page)
        }

        var lastError: SerpProviderException? = null
        for (provider in providersInOrder) {
            try {
                val r = provider.search(q, limit, page)
                return if (r.engine == provider.id) r else r.copy(engine = provider.id)
            } catch (e: SerpProviderException) {
                lastError = e
                if (!shouldDowngrade(e.code)) break
            }
        }
        throw (lastError ?: SerpProviderException("search_failed", "搜索失败"))
    }

    private fun normalizeEngine(engine: String): String {
        return when (engine) {
            "ddg" -> "duckduckgo"
            else -> engine
        }
    }

    private fun buildQuery(query: String, site: String?): String {
        val q = query.trim()
        if (q.isBlank()) throw SerpProviderException("invalid_query", "query 不能为空")
        val s = site?.trim().orEmpty()
        return if (s.isBlank()) q else "$q site:$s"
    }

    private fun shouldDowngrade(code: String): Boolean {
        return when (code) {
            "captcha",
            "http_error",
            "fetch_failed",
            "rate_limited",
            "circuit_open" -> true
            else -> false
        }
    }
}
