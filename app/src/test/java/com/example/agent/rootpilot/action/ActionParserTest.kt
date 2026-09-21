package com.example.agent.rootpilot.action

import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionParserTest {
    private val parser = ActionParser()

    @Test
    fun createTodo_normalizesTitleAndAcceptsOptionalDeadline() {
        for (due in listOf("", ",\"due_at\":null")) {
            assertEquals(
                ActionParseResult.Success(RootPilotAction.CreateTodo("买牛奶", null, "记录")),
                parser.parse("""{"action":"create_todo","title":" 买牛奶 ","reason":"记录"$due}"""),
            )
        }
        assertEquals(
            ActionParseResult.Success(RootPilotAction.CreateTodo("a".repeat(100), "2026-09-22T09:00:00+08:00", "记录")),
            parser.parse("""{"action":"create_todo","title":"${"a".repeat(100)}","due_at":"2026-09-22T09:00:00+08:00","reason":"记录"}"""),
        )
    }

    @Test
    fun createTodo_rejectsInvalidTitleDeadlineAndUnrelatedFields() {
        for (fields in listOf(
            """"title":" """",
            """"title":"${"a".repeat(101)}"""",
            """"title":null""",
            """"title":"事项","due_at":"tomorrow"""",
            """"title":"事项","due_at":"2026-09-22T09:00:00"""",
            """"title":"事项","due_at":"2026-02-30T09:00:00Z"""",
            """"title":"事项","due_at":""""",
            """"title":"事项","text":null""",
        )) {
            assertTrue(fields, parser.parse("""{"action":"create_todo",$fields,"reason":"记录"}""") is ActionParseResult.Failure)
        }
    }

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
            parser.parse("""{"action":"type","text":"hello\u0000","reason":"输入"}""")
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

    @Test
    fun acceptsLiteralUnicodeAndShellPunctuation() {
        assertEquals(
            ActionParseResult.Success(RootPilotAction.Type("中文 ' ; $(id)\n😀", "输入")),
            parser.parse("""{"action":"type","text":"中文 ' ; $(id)\n😀","reason":"输入"}"""),
        )
    }
}
