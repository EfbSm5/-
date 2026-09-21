package com.example.agent.rootpilot.ui

import com.example.agent.rootpilot.deepseek.ModelStreamSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayStreamPreviewTest {
    @Test
    fun longReasoningAndContentEachKeepOnlyTheirLast4000Characters() {
        val reasoningTail = "r".repeat(4_000)
        val contentTail = "c".repeat(4_000)
        val preview = modelPreviewText(ModelStreamSnapshot(
            reasoning = "discard reasoning prefix" + reasoningTail,
            content = "discard content prefix" + contentTail,
        ))
        assertEquals("模型输出预览（未执行）\n\n**思考**\n\n$reasoningTail\n\n**动作草稿**\n\n    $contentTail", preview)
        assertFalse(preview.contains("discard"))
    }

    @Test
    fun emptyOrWhitespaceSnapshotHasWaitingTextWithoutEmptySectionHeaders() {
        listOf(ModelStreamSnapshot(), ModelStreamSnapshot(reasoning = " \n", content = "\t ")).forEach {
            assertEquals("模型输出预览（未执行）\n\n等待模型输出…", modelPreviewText(it))
        }
    }

    @Test
    fun singlePopulatedSectionDoesNotAddHeaderForMissingSection() {
        val reasoning = modelPreviewText(ModelStreamSnapshot(reasoning = "thinking"))
        assertTrue(reasoning.contains("**思考**\n\nthinking"))
        assertFalse(reasoning.contains("动作草稿"))
        assertFalse(reasoning.contains("等待模型输出"))
        val content = modelPreviewText(ModelStreamSnapshot(content = "draft"))
        assertTrue(content.endsWith("**动作草稿**\n\n    draft"))
        assertFalse(content.contains("**思考**"))
    }

    @Test
    fun modelMarkdownFenceAndHeadingsRemainIndentedInsideActionDraft() {
        val content = "```\n# model heading\n{\"action\":\"tap\"}\n```"
        val preview = modelPreviewText(ModelStreamSnapshot(content = content))
        val draft = preview.substringAfter("**动作草稿**\n\n")
        assertEquals(content.lines().map { "    $it" }, draft.lines())
    }
}
