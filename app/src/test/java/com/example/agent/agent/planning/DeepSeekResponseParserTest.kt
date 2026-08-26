package com.example.agent.agent.planning

import org.junit.Assert.assertEquals
import org.junit.Test

class DeepSeekResponseParserTest {
    @Test
    fun parseContent_ignoresReasoningContentAndReadsMessageContent() {
        val rawResponse = """
            {
              "choices": [{
                "message": {
                  "content": "{\"goal\":\"打开设置\",\"actions\":[]}",
                  "reasoning_content": "internal reasoning"
                }
              }]
            }
        """.trimIndent()

        assertEquals(
            "{\"goal\":\"打开设置\",\"actions\":[]}",
            DeepSeekResponseParser.parseContent(rawResponse),
        )
    }
}
