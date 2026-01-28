package mumu.xsy.mumuchat.tools.search

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

object DuckDuckGoSerpParser {
    fun parse(html: String, limit: Int): List<SerpItem> {
        val doc = Jsoup.parse(html, "https://duckduckgo.com/")
        val out = ArrayList<SerpItem>(limit)
        val results = doc.select("div.result")
        for (el in results) {
            if (out.size >= limit) break
            val a = el.selectFirst("a.result__a") ?: continue
            val title = a.text().trim()
            val url = normalizeResultUrl(
                absHref = a.attr("abs:href").trim(),
                href = a.attr("href").trim()
            )
            if (title.isBlank() || url.isBlank()) continue
            val snippet = el.selectFirst("a.result__snippet")?.text()?.trim()
                ?: el.selectFirst("div.result__snippet")?.text()?.trim()
            val displayUrl = el.selectFirst("span.result__url")?.text()?.trim()
                ?: el.selectFirst("a.result__url")?.text()?.trim()
            out.add(
                SerpItem(
                    rank = out.size + 1,
                    title = title,
                    url = url,
                    displayUrl = displayUrl?.takeIf { it.isNotBlank() }?.take(120),
                    snippet = snippet?.takeIf { it.isNotBlank() }?.take(260),
                    engine = "duckduckgo"
                )
            )
        }
        return out
    }

    private fun normalizeResultUrl(absHref: String, href: String): String {
        val candidate = absHref.ifBlank { href }
        val maybeRedirect = candidate.toHttpUrlOrNull()
        if (maybeRedirect != null) {
            val uddg = maybeRedirect.queryParameter("uddg")
            if (!uddg.isNullOrBlank() && (uddg.startsWith("http://") || uddg.startsWith("https://"))) {
                return uddg
            }
        }
        return candidate
    }
}
