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

        session.messages.forEach { msg ->
            val roleName = when (msg.role) {
                MessageRole.USER -> "👤 用户"
                MessageRole.ASSISTANT -> "🤖 AI"
                MessageRole.SYSTEM -> "⚙️ 系统"
                MessageRole.TOOL -> "🔧 工具"
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

        session.messages.forEach { msg ->
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
}
