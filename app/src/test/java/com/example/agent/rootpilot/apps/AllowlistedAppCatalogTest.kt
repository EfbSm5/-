package com.example.agent.rootpilot.apps

import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.RootPilotApp
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.SuRootExecutor
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AllowlistedAppCatalogTest {
    @get:Rule val temporary = TemporaryFolder()
    private val notes = RootPilotApp("com.example.notes", "记事本", "com.example.notes.Main")
    private val settings = RootPilotApp("com.android.settings", "设置", "com.android.settings.Settings")

    @Test
    fun filtersRawCatalogAndReadsLatestSelectionOnEveryCall() {
        val file = File(temporary.root, "selection.json")
        val store = AppLaunchAllowlistStore(file)
        var apps = listOf(notes, settings)
        val catalog = AllowlistedAppCatalog(AppCatalog { apps }, store)
        assertTrue(catalog.listApps().isEmpty())
        AppLaunchAllowlistStore(file).save(setOf(settings.packageName, "com.example.notinstalled"))
        assertEquals(listOf(settings), catalog.listApps())
        apps = listOf(notes)
        assertTrue(catalog.listApps().isEmpty())
        AppLaunchAllowlistStore(file).save(setOf(notes.packageName))
        assertEquals(listOf(notes), catalog.listApps())
        file.writeText("broken JSON")
        assertTrue(catalog.listApps().isEmpty())
        store.save(setOf(notes.packageName))
        assertEquals(listOf(notes), catalog.listApps())
        store.save(emptySet())
        assertTrue(catalog.listApps().isEmpty())
    }

    @Test
    fun revokingSelectionWhileAwaitingApprovalRejectsBeforeShellExecution() = runTest {
        val file = File(temporary.root, "selection.json")
        val store = AppLaunchAllowlistStore(file)
        store.save(setOf(notes.packageName))
        val catalog = AllowlistedAppCatalog(AppCatalog { listOf(notes) }, store)
        val action = ExecutableRootAction.OpenApp(catalog.listApps().single())
        val executor = SuRootExecutor(appCatalog = catalog)
        var confirmations = 0
        val result = executor.executeConfirmed(action) {
            confirmations++
            AppLaunchAllowlistStore(file).save(emptySet())
            true
        }
        assertEquals(1, confirmations)
        assertEquals(RootExecutionResult.Failure("应用已不在可启动列表中，请重新观察"), result)
    }
}
