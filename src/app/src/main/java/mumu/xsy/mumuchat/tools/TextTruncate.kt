package mumu.xsy.mumuchat.tools

object TextTruncate {
    data class Result(val text: String, val truncated: Boolean, val length: Int)

    fun limit(input: String, maxChars: Int): Result {
        if (maxChars <= 0) return Result("", truncated = input.isNotEmpty(), length = input.length)
        val truncated = input.length > maxChars
        val text = if (truncated) input.take(maxChars) else input
        return Result(text = text, truncated = truncated, length = input.length)
    }
}
