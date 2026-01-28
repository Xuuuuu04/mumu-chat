package mumu.xsy.mumuchat.tools.search

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SerpSearchSmokeTest {
    @Test
    fun specified_engine_routes_to_provider() = runBlocking {
        val p1 = object : SerpProvider {
            override val id: String = "baidu"
            override suspend fun search(query: String, limit: Int, page: Int): SerpResponse {
                return SerpResponse(
                    engine = id,
                    results = listOf(SerpItem(rank = 1, title = "t", url = "https://example.com", engine = id))
                )
            }
        }
        val p2 = object : SerpProvider {
            override val id: String = "duckduckgo"
            override suspend fun search(query: String, limit: Int, page: Int): SerpResponse {
                throw SerpProviderException("should_not_call", "should_not_call")
            }
        }
        val service = SerpSearchService(listOf(p1, p2))
        val r = service.search(query = "OpenAI", engine = "baidu", limit = 5, page = 1, site = null)
        assertEquals("baidu", r.engine)
        assertTrue(r.results.isNotEmpty())
    }

    @Test
    fun auto_search_downgrades_and_sets_meta() = runBlocking {
        val failing = object : SerpProvider {
            override val id: String = "first"
            override suspend fun search(query: String, limit: Int, page: Int): SerpResponse {
                throw SerpProviderException("captcha", "captcha")
            }
        }
        val ok = object : SerpProvider {
            override val id: String = "second"
            override suspend fun search(query: String, limit: Int, page: Int): SerpResponse {
                return SerpResponse(
                    engine = id,
                    results = listOf(SerpItem(rank = 1, title = "t", url = "https://example.com", engine = id))
                )
            }
        }

        val service = SerpSearchService(listOf(failing, ok))
        val r = service.search(query = "OpenAI", engine = "auto", limit = 5, page = 1, site = null)
        assertEquals("second", r.engine)
        assertEquals("first", r.downgradedFrom)
    }
}
