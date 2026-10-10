package com.example.agent.rootpilot

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * `createComposeRule()` 用例类的 opt-in 门控（原因与证据见 `TASKS.md` #17）。
 *
 * 这些类在 onCreate 阶段就启动宿主 Activity；在小米 15（MIUI）上第二个同类 Activity 启动会被
 * `Abort background activity starts` 拦掉，随后 `Instrumentation.startActivitySync` 永久等待，
 * 整个插桩进程以 `Process crashed` 结束（不是用例断言失败）。此外它们依赖
 * `debugImplementation(libs.kotlinx.coroutines.test)` 才能在 `runTest` 里拿到
 * `TestMainDispatcher`（默认集合里没有该依赖，`Exception handler was not found via a ServiceLoader`）。
 *
 * 因此默认跳过，只有显式传 `-e composeUiTests=true` 时才执行：
 * ```
 * adb shell am instrument -w -e composeUiTests true \
 *   com.example.agent.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * 门控必须早于 compose rule 启动 Activity，所以在用例类里用 `RuleChain` 包在 compose rule 外层，
 * 不能写成 `@Before` 里的 `assumeTrue`（那时 Activity 已经启动了）。
 */
class ComposeUiTestGate : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val enabled = InstrumentationRegistry.getArguments()
                    .getString(ARG_ENABLED)
                    ?.equals("true", ignoreCase = true) == true
                assumeTrue(
                    "createComposeRule 用例默认跳过（两个成因见 TASKS.md #17）。" +
                        "显式运行请加 -e $ARG_ENABLED=true，" +
                        "并确认已按 #17 的 A 配方把 kotlinx-coroutines-test 加进 debug 依赖。",
                    enabled,
                )
                base.evaluate()
            }
        }

    companion object {
        /** 与 `-e composeUiTests=true` 对应的门控开关名。 */
        const val ARG_ENABLED = "composeUiTests"
    }
}
