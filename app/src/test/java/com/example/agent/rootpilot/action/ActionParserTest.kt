package com.example.agent.rootpilot.action

import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionParserTest {
    private val parser = ActionParser()

    @Test
    fun parsesAllActionKinds() {
        assertTrue(parser.parse("""{"action":"tap","x":1,"y":2,"reason":"点击"}""") is ActionParseResult.Success)
        assertTrue(
            parser.parse(
                """{"action":"swipe","x1":1,"y1":2,"x2":3,"y2":4,"duration_ms":300,"reason":"滑动"}""",
            ) is ActionParseResult.Success,
        )
        assertEquals(
            ActionParseResult.Success(
                RootPilotAction.OpenApp("com.android.settings", "打开设置"),
            ),
            parser.parse(
                """{"action":"open_app","package_name":"com.android.settings","reason":"打开设置"}""",
            ),
        )
        assertEquals(
            ActionParseResult.Success(RootPilotAction.Type("hello", "输入")),
            parser.parse("""{"action":"type","text":"hello","reason":"输入"}"""),
        )
        assertEquals(
            ActionParseResult.Success(RootPilotAction.Key(RootPilotKey.BACK, "返回")),
            parser.parse("""{"action":"key","key":"BACK","reason":"返回"}"""),
        )
        assertTrue(
            parser.parse("""{"action":"wait","duration_ms":300,"reason":"等待"}""") is ActionParseResult.Success,
        )
        assertEquals(
            ActionParseResult.Success(RootPilotAction.AskUser("请接管")),
            parser.parse("""{"action":"ask_user","message":"请接管"}"""),
        )
        assertEquals(
            ActionParseResult.Success(RootPilotAction.Finish(true, "完成")),
            parser.parse("""{"action":"finish","success":true,"message":"完成"}"""),
        )
    }

    @Test
    fun rejectsInvalidJsonAndUnknownFields() {
        assertTrue(parser.parse("not-json") is ActionParseResult.Failure)
        assertTrue(
            parser.parse("""{"action":"tap","x":1,"y":2,"reason":"点击","shell":"rm"}""")
                is ActionParseResult.Failure,
        )
    }

    @Test
    fun rejectsInvalidCoordinatesDurationKeyAndText() {
        assertTrue(
            parser.parse("""{"action":"tap","x":1001,"y":2,"reason":"点击"}""")
                is ActionParseResult.Failure,
        )
        assertTrue(
            parser.parse("""{"action":"wait","duration_ms":299,"reason":"等待"}""")
                is ActionParseResult.Failure,
        )
        assertTrue(
            parser.parse("""{"action":"key","key":"DELETE","reason":"危险"}""")
                is ActionParseResult.Failure,
        )
        assertTrue(
            parser.parse("""{"action":"type","text":"hello;rm","reason":"输入"}""")
                is ActionParseResult.Failure,
        )
        assertTrue(
            parser.parse(
                """{"action":"open_app","package_name":"com.android.settings;rm","reason":"打开设置"}""",
            ) is ActionParseResult.Failure,
        )
        assertTrue(
            parser.parse(
                """{"action":"type","text":"${"a".repeat(129)}","reason":"输入"}""",
            ) is ActionParseResult.Failure,
        )
    }
}
