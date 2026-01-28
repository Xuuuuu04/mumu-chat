package mumu.xsy.mumuchat.tools.search

data class SerpItem(
    val rank: Int,
    val title: String,
    val url: String,
    val displayUrl: String? = null,
    val snippet: String? = null,
    val engine: String
)

data class SerpResponse(
    val engine: String,
    val results: List<SerpItem>,
    val latencyMs: Long? = null,
    val cached: Boolean? = null,
    val downgradedFrom: String? = null
)

