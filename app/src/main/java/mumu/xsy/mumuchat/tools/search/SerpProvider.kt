package mumu.xsy.mumuchat.tools.search

interface SerpProvider {
    val id: String
    suspend fun search(query: String, limit: Int, page: Int): SerpResponse
}

class SerpProviderException(
    val code: String,
    override val message: String
) : RuntimeException(message)

