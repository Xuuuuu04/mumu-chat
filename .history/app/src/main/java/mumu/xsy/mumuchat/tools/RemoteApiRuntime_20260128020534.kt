package mumu.xsy.mumuchat.tools

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class RemoteApiCache {
    private data class Entry(val body: String, val at: Long, val contentType: String?)
    private val map = ConcurrentHashMap<String, Entry>()

    fun get(key: String, ttlMs: Long): Pair<String, String?>? {
        val e = map[key] ?: return null
        if (System.currentTimeMillis() - e.at > ttlMs) return null
        return e.body to e.contentType
    }

    fun put(key: String, body: String, contentType: String?) {
        map[key] = Entry(body = body, at = System.currentTimeMillis(), contentType = contentType)
    }
}

class FixedWindowRateLimiter(
    private val windowMs: Long = 60_000L
) {
    private data class Window(var startMs: Long, var count: Int)
    private val windows = ConcurrentHashMap<String, Window>()

    fun allow(key: String, maxPerWindow: Int): Boolean {
        val now = System.currentTimeMillis()
        val w = windows.compute(key) { _, existing ->
            val cur = existing ?: Window(startMs = now, count = 0)
            if (now - cur.startMs >= windowMs) {
                cur.startMs = now
                cur.count = 0
            }
            cur
        }!!
        synchronized(w) {
            if (w.count >= maxPerWindow) return false
            w.count += 1
            return true
        }
    }
}

class SimpleCircuitBreaker(
    private val failureThreshold: Int = 3,
    private val cooldownMs: Long = 5 * 60_000L
) {
    private data class State(var failures: Int, var openUntilMs: Long)
    private val states = ConcurrentHashMap<String, State>()

    fun isOpen(key: String): Boolean {
        val s = states[key] ?: return false
        return System.currentTimeMillis() < s.openUntilMs
    }

    fun recordSuccess(key: String) {
        states.remove(key)
    }

    fun recordFailure(key: String) {
        val now = System.currentTimeMillis()
        val s = states.compute(key) { _, existing ->
            val cur = existing ?: State(failures = 0, openUntilMs = 0L)
            cur.failures += 1
            if (cur.failures >= failureThreshold) {
                cur.openUntilMs = now + cooldownMs
            }
            cur
        }!!
        if (now >= s.openUntilMs && s.failures >= failureThreshold) {
            s.openUntilMs = now + cooldownMs
        }
    }

    fun snapshot(key: String): Pair<Int, Long>? {
        val s = states[key] ?: return null
        return s.failures to s.openUntilMs
    }
}

data class RemoteFetchResult(
    val ok: Boolean,
    val code: String? = null,
    val httpCode: Int? = null,
    val message: String? = null,
    val finalUrl: String? = null,
    val body: String? = null,
    val contentType: String? = null,
    val latencyMs: Long? = null
)

class RemoteApiClient(
    private val baseClient: OkHttpClient,
    private val cache: RemoteApiCache,
    private val limiter: FixedWindowRateLimiter,
    private val breaker: SimpleCircuitBreaker
) {
    fun fetch(endpoint: RemoteEndpoint): RemoteFetchResult {
        if (breaker.isOpen(endpoint.id)) {
            val snap = breaker.snapshot(endpoint.id)
            return RemoteFetchResult(
                ok = false,
                code = "circuit_open",
                message = "端点暂时不可用（熔断中）",
                latencyMs = 0L,
                httpCode = null,
                finalUrl = endpoint.url,
                body = null,
                contentType = null
            ).copy(message = "端点暂时不可用（熔断中）")
        }
        if (!limiter.allow(endpoint.id, endpoint.maxPerMinute)) {
            return RemoteFetchResult(ok = false, code = "rate_limited", message = "请求过于频繁，请稍后重试")
        }
        cache.get(endpoint.id, endpoint.ttlMs)?.let { (cached, ct) ->
            return RemoteFetchResult(ok = true, code = "cached", finalUrl = endpoint.url, body = cached, contentType = ct, latencyMs = 0L)
        }

        val start = System.currentTimeMillis()
        val client = baseClient.newBuilder()
            .connectTimeout(endpoint.timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(endpoint.timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(endpoint.timeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val followResult = if (endpoint.followRedirects) {
            RedirectFollower.follow(
                startUrl = endpoint.url,
                maxRedirects = 5,
                fetch = { url ->
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", "MuMuChat/2.1")
                        .get()
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isRedirect) {
                            RedirectFollower.FetchResult(statusCode = resp.code, location = resp.header("Location"))
                        } else {
                            val src = resp.body?.source()
                            val buffer = Buffer()
                            if (src != null) {
                                while (buffer.size < endpoint.maxBytes) {
                                    val toRead = minOf(8192L, endpoint.maxBytes - buffer.size)
                                    val read = src.read(buffer, toRead)
                                    if (read == -1L) break
                                }
                            }
                            RedirectFollower.FetchResult(statusCode = resp.code, body = buffer.readUtf8())
                        }
                    }
                },
                resolve = { base, location ->
                    val baseUrl = base.toHttpUrlOrNull() ?: return@follow null
                    baseUrl.resolve(location)?.toString()
                }
            )
        } else {
            RedirectFollower.Result(ok = true, finalUrl = endpoint.url, body = null)
        }

        if (!followResult.ok) {
            breaker.recordFailure(endpoint.id)
            return RemoteFetchResult(
                ok = false,
                code = followResult.code ?: "fetch_failed",
                message = followResult.message ?: "请求失败",
                latencyMs = System.currentTimeMillis() - start,
                finalUrl = endpoint.url
            )
        }

        val url = followResult.finalUrl ?: endpoint.url
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "MuMuChat/2.1")
            .get()
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                val http = resp.code
                val ct = resp.header("Content-Type")
                val src = resp.body?.source()
                val buffer = Buffer()
                if (src != null) {
                    while (buffer.size < endpoint.maxBytes) {
                        val toRead = minOf(8192L, endpoint.maxBytes - buffer.size)
                        val read = src.read(buffer, toRead)
                        if (read == -1L) break
                    }
                }
                val body = buffer.readUtf8()
                if (!resp.isSuccessful) {
                    breaker.recordFailure(endpoint.id)
                    RemoteFetchResult(
                        ok = false,
                        code = "http_error",
                        httpCode = http,
                        message = "HTTP $http",
                        latencyMs = System.currentTimeMillis() - start,
                        finalUrl = url,
                        body = body,
                        contentType = ct
                    )
                } else {
                    breaker.recordSuccess(endpoint.id)
                    cache.put(endpoint.id, body, ct)
                    RemoteFetchResult(
                        ok = true,
                        httpCode = http,
                        latencyMs = System.currentTimeMillis() - start,
                        finalUrl = url,
                        body = body,
                        contentType = ct
                    )
                }
            }
        } catch (e: Exception) {
            breaker.recordFailure(endpoint.id)
            RemoteFetchResult(
                ok = false,
                code = "connect_failed",
                message = e.message ?: "connect_failed",
                latencyMs = System.currentTimeMillis() - start,
                finalUrl = url
            )
        }
    }
}
