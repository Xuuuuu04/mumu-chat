package mumu.xsy.mumuchat.tools.search

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SerpSearchServiceDowngradeTest {
    @Test
    fun auto_search_downgrades_on_connect_failed() = runBlocking {
        val failing = object : SerpProvider {
            override val id: String = "first"
            override suspend fun search(query: String, limit: Int, page: Int): SerpResponse {
                throw SerpProviderException("connect_failed", "network down")
            }
        }
        val ok = object : SerpProvider {
            override val id: String = "second"
            override suspend fun search(query: String, limit: Int, page: Int): SerpResponse {
                return SerpResponse(
                    engine = id,
                    results = listOf(
                        SerpItem(rank = 1, title = "t", url = "https://example.com", engine = id)
                    )
                )
            }
        }

        val service = SerpSearchService(listOf(failing, ok))
        val r = service.search(query = "q", engine = "auto", limit = 5, page = 1, site = null)
        assertEquals("second", r.engine)
        assertTrue(r.results.isNotEmpty())
    }
}

