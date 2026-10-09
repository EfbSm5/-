package com.example.agent.rootpilot.input

/** Runtime-only bounded plain-text edit; source contents never enter logs or recovery snapshots. */
internal class VirtualTextEditPlan private constructor(
    private val original: String,
    private val start: Int,
    private val end: Int,
    val replacement: String,
    val caret: Int,
) {
    fun matchesSource(text: CharSequence?, selectionStart: Int, selectionEnd: Int): Boolean =
        text != null && text.length == original.length && original.contentEquals(text) &&
            selectionStart == start && selectionEnd == end

    fun matchesResult(text: CharSequence?, selectionStart: Int, selectionEnd: Int): Boolean =
        matchesResultText(text) && selectionStart == caret && selectionEnd == caret

    fun matchesResultText(text: CharSequence?): Boolean =
        text != null && text.length == replacement.length && replacement.contentEquals(text)

    override fun toString() = "VirtualTextEditPlan"

    companion object {
        fun create(text: CharSequence?, selectionStart: Int, selectionEnd: Int, input: String): VirtualTextEditPlan? {
            if (text == null || text.length > InputText.MAX_LENGTH || !InputText.isValid(input)) return null
            val original = text.toString()
            if (original.isNotEmpty() && !InputText.isValid(original)) return null
            if (selectionStart !in 0..original.length || selectionEnd !in 0..original.length ||
                !boundary(original, selectionStart) || !boundary(original, selectionEnd)) return null
            val from = minOf(selectionStart, selectionEnd)
            val to = maxOf(selectionStart, selectionEnd)
            val replacement = original.substring(0, from) + input + original.substring(to)
            if (!InputText.isValid(replacement)) return null
            return VirtualTextEditPlan(original, selectionStart, selectionEnd, replacement, from + input.length)
        }

        private fun boundary(text: String, index: Int): Boolean =
            index == 0 || index == text.length || !text[index - 1].isHighSurrogate() || !text[index].isLowSurrogate()
    }
}
