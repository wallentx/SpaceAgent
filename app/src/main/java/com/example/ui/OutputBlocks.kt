package com.example.ui

internal data class OutputBlock(val text: String, val isCode: Boolean)

/** Keep prose and every fenced block in order, regardless of the fence language. */
internal fun parseOutputBlocks(output: String): List<OutputBlock> {
    val blocks = mutableListOf<OutputBlock>()
    val lines = mutableListOf<String>()
    var fence: String? = null
    fun flush(code: Boolean) {
        val text = lines.joinToString("\n")
        if (text.isNotBlank()) blocks += OutputBlock(text, code)
        lines.clear()
    }
    for (line in output.lineSequence()) {
        val current = fence
        if (current == null) {
            val opening = Regex("^ {0,3}(`{3,}|~{3,})(.*)$").matchEntire(line)
            if (opening != null) {
                flush(false)
                fence = opening.groupValues[1]
            } else lines += line
        } else {
            val trimmed = line.trim()
            if (trimmed.length >= current.length && trimmed.all { it == current.first() }) {
                flush(true)
                fence = null
            } else lines += line
        }
    }
    flush(fence != null)
    return blocks
}
