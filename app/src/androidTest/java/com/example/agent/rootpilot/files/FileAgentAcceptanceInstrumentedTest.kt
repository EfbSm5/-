package com.example.agent.rootpilot.files

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.RootPilotActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in live API acceptance, restricted to an explicitly selected empty synthetic workspace. */
@RunWith(AndroidJUnit4::class)
class FileAgentAcceptanceInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var activity: RootPilotActivity? = null

    @After fun finish() {
        activity?.let { target -> instrumentation.runOnMainSync { target.finish() } }
    }

    @Test fun confirmedCreateEditAndBackupExport() = runBlocking {
        val folder = "RootPilot-Acceptance-20260924-1700"
        assumeTrue(InstrumentationRegistry.getArguments().getString("rootpilotFileAcceptance") == folder)
        val context = instrumentation.targetContext
        val workspace = FileWorkspace(context)
        assertEquals(folder, workspace.selectedLabel())
        // Never reuse a previous run or read a user's populated directory.
        assertTrue("Acceptance directory must be empty", workspace.list().isEmpty())
        val previousBackups = workspace.listBackups().map { it.id }.toSet()
        activity = instrumentation.startActivitySync(Intent(context, RootPilotActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as RootPilotActivity
        compose.onNodeWithTag("open_chat").performClick()
        compose.onNodeWithTag("file_agent_manage").performClick()
        compose.onNodeWithTag("file_agent_enable").performScrollTo().performClick()
        compose.onNodeWithTag("chat_effort_HIGH").performClick()

        fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        fun send(prompt: String) {
            compose.onNodeWithTag("chat_draft").performTextReplacement(prompt)
            compose.onNodeWithTag("chat_send").assertIsEnabled().performClick()
        }
        fun approve(before: String, after: String) {
            compose.waitUntil(150_000) { exists("file_write_confirm") || !exists("chat_stop") }
            compose.onNode(hasText("acceptance.txt") and hasAnyAncestor(isDialog())).assertExists()
            compose.onNodeWithTag("file_diff_toggle").performScrollTo().performClick()
            compose.onNodeWithTag("file_before").assertTextEquals(before)
            compose.onNodeWithTag("file_after").assertTextEquals(after)
            compose.onNodeWithTag("file_write_confirm").performClick()
            compose.waitUntil(150_000) { !exists("chat_stop") }
        }

        send("这是专用空目录验收。请使用文件工具创建 acceptance.txt，内容严格为 ROOTPILOT_TEST_ALPHA，不要换行，不操作其他文件。创建完成后简短回复。")
        approve("（新文件）", "ROOTPILOT_TEST_ALPHA")
        assertEquals("ROOTPILOT_TEST_ALPHA", workspace.read("acceptance.txt"))

        send("请先读取 acceptance.txt，再使用编辑工具将唯一的 ROOTPILOT_TEST_ALPHA 替换为 ROOTPILOT_TEST_BETA。不加换行，不操作其他文件。")
        approve("ROOTPILOT_TEST_ALPHA", "ROOTPILOT_TEST_BETA")
        assertEquals("ROOTPILOT_TEST_BETA", workspace.read("acceptance.txt"))
        val backup = workspace.listBackups().filter { it.id !in previousBackups }.single()
        val bytes = workspace.backupBytes(backup.id)
        assertEquals("ROOTPILOT_TEST_ALPHA", bytes.toString(Charsets.UTF_8))

        val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Documents/$folder")
        val parent: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val target = requireNotNull(DocumentsContract.createDocument(context.contentResolver, parent, "text/plain", "original.txt"))
        exportBackupDocument(context, target, bytes)
        assertEquals("ROOTPILOT_TEST_ALPHA", workspace.read("original.txt"))
        val reopened = FileWorkspace(context)
        assertEquals(folder, reopened.selectedLabel())
        assertTrue(reopened.listBackups().any { it.id == backup.id })
    }
}
