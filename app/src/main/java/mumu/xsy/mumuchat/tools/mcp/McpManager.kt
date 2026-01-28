package mumu.xsy.mumuchat.tools.mcp

import mumu.xsy.mumuchat.McpServerConfig
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

class McpManager(
    private val http: OkHttpClient
) {
    private val clients = ConcurrentHashMap<String, McpHttpClient>()

    fun get(config: McpServerConfig): McpHttpClient {
        val key = "${config.id}|${config.endpoint.trim()}|${config.authToken.orEmpty()}"
        return clients.computeIfAbsent(key) {
            McpHttpClient(http = http, endpoint = config.endpoint.trim(), authToken = config.authToken)
        }
    }
}

