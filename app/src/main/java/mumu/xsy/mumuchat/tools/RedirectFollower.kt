package mumu.xsy.mumuchat.tools

object RedirectFollower {
    data class FetchResult(
        val statusCode: Int,
        val location: String? = null,
        val body: String? = null
    )

    data class Result(
        val ok: Boolean,
        val finalUrl: String? = null,
        val body: String? = null,
        val code: String? = null,
        val message: String? = null
    )

    fun follow(
        startUrl: String,
        maxRedirects: Int,
        fetch: (String) -> FetchResult,
        resolve: (base: String, location: String) -> String?
    ): Result {
        var current = startUrl
        for (hop in 0..maxRedirects) {
            val r = fetch(current)
            if (r.statusCode in 300..399) {
                val loc = r.location ?: return Result(false, code = "redirect_no_location", message = "重定向无 Location")
                val next = resolve(current, loc) ?: return Result(false, code = "redirect_invalid", message = "无法解析重定向 URL")
                if (hop == maxRedirects) return Result(false, code = "too_many_redirects", message = "重定向次数过多")
                current = next
                continue
            }
            if (r.statusCode !in 200..299) return Result(false, code = "http_error", message = "HTTP ${r.statusCode}")
            return Result(true, finalUrl = current, body = r.body.orEmpty())
        }
        return Result(false, code = "too_many_redirects", message = "重定向次数过多")
    }
}
