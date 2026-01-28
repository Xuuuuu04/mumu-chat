package mumu.xsy.mumuchat.tools.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaiduSerpParserTest {
    @Test
    fun detectsCaptcha() {
        assertTrue(BaiduSerpParser.looksLikeCaptcha("<html><title>百度安全验证</title></html>"))
        assertTrue(BaiduSerpParser.looksLikeCaptcha("安全验证 captcha baidu"))
        assertFalse(BaiduSerpParser.looksLikeCaptcha("<html><title>正常</title></html>"))
    }

    @Test
    fun parsesBasicResults() {
        val html = """
            <html><body>
            <div id="content_left">
              <div>
                <h3><a href="http://www.baidu.com/link?url=abc">示例标题</a></h3>
                <div class="c-abstract">示例摘要内容</div>
                <a class="c-showurl">example.com</a>
              </div>
            </div>
            </body></html>
        """.trimIndent()
        val items = BaiduSerpParser.parse(html, 10)
        assertEquals(1, items.size)
        assertEquals("示例标题", items[0].title)
        assertEquals("http://www.baidu.com/link?url=abc", items[0].url)
        assertEquals("example.com", items[0].displayUrl)
        assertEquals("示例摘要内容", items[0].snippet)
        assertEquals("baidu", items[0].engine)
    }
}

