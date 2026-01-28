package mumu.xsy.mumuchat.tools.search

import org.jsoup.Jsoup
import java.util.Locale

object BaiduSerpParser {
    fun looksLikeCaptcha(html: String): Boolean {
        val h = html.lowercase(Locale.ROOT)
        if (h.contains("百度安全验证") || h.contains("安全验证")) return true
        if (h.contains("captcha") && (h.contains("baidu") || h.contains("verify"))) return true
        return false
    }

    fun parse(html: String, limit: Int): List<SerpItem> {
        val doc = Jsoup.parse(html, "https://www.baidu.com/")
        val out = ArrayList<SerpItem>(limit)
        val containers = doc.select("#content_left > div")
        for (el in containers) {
            if (out.size >= limit) break
            val a = el.selectFirst("h3 a") ?: continue
            val title = a.text().trim()
            val href = a.attr("href").trim()
            if (title.isBlank() || href.isBlank()) continue
            val url = normalizeBaiduHref(href)
            val snippet = extractSnippet(el)
            val displayUrl = extractDisplayUrl(el)
            out.add(
                SerpItem(
                    rank = out.size + 1,
                    title = title,
                    url = url,
                    displayUrl = displayUrl,
                    snippet = snippet,
                    engine = "baidu"
                )
            )
        }
        return out
    }

    private fun normalizeBaiduHref(href: String): String {
        val h = href.trim()
        if (h.startsWith("http://", true) || h.startsWith("https://", true)) return h
        if (h.startsWith("//")) return "https:$h"
        if (h.startsWith("/")) return "https://www.baidu.com$h"
        return "https://www.baidu.com/$h"
    }

    private fun extractSnippet(el: org.jsoup.nodes.Element): String? {
        val s = el.selectFirst("div.c-abstract")?.text()?.trim()
            ?: el.selectFirst("span.content-right_8Zs40")?.text()?.trim()
            ?: el.selectFirst("div[class*=abstract]")?.text()?.trim()
            ?: el.selectFirst("div[class*=summary]")?.text()?.trim()
            ?: el.selectFirst("div[class*=desc]")?.text()?.trim()
        return s?.takeIf { it.isNotBlank() }?.take(260)
    }

    private fun extractDisplayUrl(el: org.jsoup.nodes.Element): String? {
        val t = el.selectFirst("a.c-showurl")?.text()?.trim()
            ?: el.selectFirst("span.c-color-gray")?.text()?.trim()
            ?: el.selectFirst("span[class*=showurl]")?.text()?.trim()
        return t?.takeIf { it.isNotBlank() }?.take(120)
    }
}

