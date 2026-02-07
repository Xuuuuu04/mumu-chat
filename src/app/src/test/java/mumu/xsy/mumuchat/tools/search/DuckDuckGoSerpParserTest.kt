package mumu.xsy.mumuchat.tools.search

import org.junit.Assert.assertEquals
import org.junit.Test

class DuckDuckGoSerpParserTest {
    @Test
    fun parsesBasicResults() {
        val html = """
            <html><body>
              <div class="results">
                <div class="result">
                  <a class="result__a" href="https://example.com/a">Example A</a>
                  <a class="result__snippet">Snippet A</a>
                  <span class="result__url">example.com</span>
                </div>
                <div class="result">
                  <a class="result__a" href="https://example.com/b">Example B</a>
                  <div class="result__snippet">Snippet B</div>
                </div>
              </div>
            </body></html>
        """.trimIndent()
        val items = DuckDuckGoSerpParser.parse(html, 10)
        assertEquals(2, items.size)
        assertEquals("Example A", items[0].title)
        assertEquals("https://example.com/a", items[0].url)
        assertEquals("Snippet A", items[0].snippet)
        assertEquals("example.com", items[0].displayUrl)
        assertEquals("duckduckgo", items[0].engine)
    }
}

