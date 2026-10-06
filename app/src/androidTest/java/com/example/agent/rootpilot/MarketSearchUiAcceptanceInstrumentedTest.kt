package com.example.agent.rootpilot

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.information.AndroidUiTreeProvider
import com.example.agent.rootpilot.information.RootPilotAccessibilityService
import com.example.agent.rootpilot.root.SuRootExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in inspection of public market search controls; no query, network or service command. */
@RunWith(AndroidJUnit4::class)
class MarketSearchUiAcceptanceInstrumentedTest {
    @Test fun inspectPublicMarketPageWithoutActionsOrNetwork() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("marketSearchPreflight") == "true")
        assertTrue("accessibility_connection_required", RootPilotAccessibilityService.connected.value)
        val tree = marketTree()
        val controls = tree.nodes.map { node -> buildJsonObject {
            for (field in listOf("node_id", "parent_id", "resource_id", "class", "bounds",
                "clickable", "editable", "enabled", "focused", "selected", "text_redacted")) {
                node[field]?.let { put(field, it) }
            }
            put("safeLabels", JsonArray(labels(node).filter { it in SAFE_LABELS }.map(::JsonPrimitive)))
            put("textEmpty", node["text"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty())
        } }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("marketControls", JsonArray(controls).toString())
            putString("marketActivity", tree.activity)
            putBoolean("marketTreeTruncated", tree.truncated)
            putBoolean("rootpilotIdle", !RootPilotService.uiState.value.running)
            putBoolean("mainDisplayConfigured", RootPilotService.uiState.value.config.executionDisplay ==
                com.example.agent.rootpilot.model.ExecutionDisplay.MAIN)
        })
    }

    private data class MarketTree(val nodes: List<JsonObject>, val truncated: Boolean, val activity: String)

    private suspend fun marketTree(): MarketTree {
        val observer = SuRootExecutor()
        val before = observer.observeScreen()
        assertEquals("market_foreground_required", MARKET, before.foregroundPackage)
        assertEquals("market_focus_required", MARKET, before.focusedPackage)
        assertTrue("market_activity_required", before.foregroundActivity in MARKET_ACTIVITIES)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = AndroidUiTreeProvider(context.packageName).query(before)
        assertNull("market_tree_unavailable", result.unavailable)
        val after = observer.observeScreen()
        assertEquals("market_window_changed", before.focusedWindowId, after.focusedWindowId)
        assertEquals("market_foreground_changed", before.foregroundPackage, after.foregroundPackage)
        assertEquals("market_focus_changed", before.focusedPackage, after.focusedPackage)
        assertEquals("market_activity_changed", before.foregroundActivity, after.foregroundActivity)
        val data = result.data ?: throw AssertionError("market_tree_missing")
        assertEquals(MARKET, data["package_name"]?.jsonPrimitive?.contentOrNull)
        return MarketTree(data.getValue("nodes").jsonArray.map { it.jsonObject }, result.truncated,
            before.foregroundActivity!!)
    }

    private fun labels(node: JsonObject) = listOf("text", "content_description", "hint")
        .mapNotNull { node[it]?.jsonPrimitive?.contentOrNull }

    private companion object {
        const val MARKET = "com.xiaomi.market"
        val MARKET_ACTIVITIES = setOf("$MARKET.ui.DefaultLauncherIcon", "$MARKET.business_ui.main.MarketTabActivity",
            "$MARKET.ui.SearchActivityPhone")
        val SAFE_LABELS = setOf("首页", "搜索", "取消", "计算器", "安装", "打开", "下载")
    }
}
