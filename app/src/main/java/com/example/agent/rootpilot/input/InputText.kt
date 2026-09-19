package com.example.agent.rootpilot.input

object InputText {
    const val MAX_LENGTH = 128

    fun isValid(text: String): Boolean {
        if (text.isEmpty() || text.length > MAX_LENGTH) return false
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            when {
                char.isHighSurrogate() -> {
                    if (index >= text.length || !text[index++].isLowSurrogate()) return false
                }
                char.isLowSurrogate() -> return false
                char.isISOControl() && char != '\n' && char != '\t' -> return false
            }
        }
        return true
    }
}
