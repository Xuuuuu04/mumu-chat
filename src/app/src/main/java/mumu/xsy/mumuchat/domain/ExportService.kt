package mumu.xsy.mumuchat.domain

import mumu.xsy.mumuchat.ChatSession
import mumu.xsy.mumuchat.MessageRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExportService {
    fun exportSessionToMarkdown(session: ChatSession): String {
        val sb = StringBuilder()
        sb.appendLine("# ${session.title}")
        sb.appendLine()
        sb.appendLine("> 导出时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        sb.appendLine()

        session.messages.orEmpty().forEach { msg ->
            val roleName = when (msg.role) {
                MessageRole.USER -> "用户"
                MessageRole.ASSISTANT -> "AI 助手"
                MessageRole.SYSTEM -> "系统"
                MessageRole.TOOL -> "工具"
            }
            sb.appendLine("## $roleName")
            sb.appendLine()
            sb.appendLine(msg.content)
            sb.appendLine()
            sb.appendLine("---")
            sb.appendLine()
        }

        return sb.toString()
    }

    fun exportSessionToPlainText(session: ChatSession): String {
        val sb = StringBuilder()
        sb.appendLine(session.title)
        sb.appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        sb.appendLine()

        session.messages.orEmpty().forEach { msg ->
            val roleName = when (msg.role) {
                MessageRole.USER -> "用户"
                MessageRole.ASSISTANT -> "AI"
                MessageRole.SYSTEM -> "系统"
                MessageRole.TOOL -> "工具"
            }
            sb.appendLine("[$roleName]")
            sb.appendLine(msg.content)
            sb.appendLine()
        }

        return sb.toString().trimEnd()
    }

    fun exportSessionToHtml(session: ChatSession): String {
        val text = exportSessionToPlainText(session)
        val escaped = buildString {
            text.forEach { ch ->
                when (ch) {
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '&' -> append("&amp;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(ch)
                }
            }
        }
        return """
            <!doctype html>
            <html>
              <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1" />
                <title>${escapeInline(session.title)}</title>
                <style>
                  body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; padding: 16px; }
                  pre { white-space: pre-wrap; word-wrap: break-word; line-height: 1.55; }
                </style>
              </head>
              <body>
                <pre>$escaped</pre>
              </body>
            </html>
        """.trimIndent()
    }

    private fun escapeInline(text: String): String {
        return buildString {
            text.forEach { ch ->
                when (ch) {
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '&' -> append("&amp;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(ch)
                }
            }
        }
    }
}
