package mumu.xsy.mumuchat.tools.mcp

class McpException(
    val code: String,
    override val message: String
) : RuntimeException(message)

