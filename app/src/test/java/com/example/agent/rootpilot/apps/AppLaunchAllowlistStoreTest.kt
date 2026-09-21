package com.example.agent.rootpilot.apps

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppLaunchAllowlistStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun missingFileDefaultsToNoSelection() {
        assertEquals(emptySet<String>(), AppLaunchAllowlistStore(File(temporary.root, "missing.json")).read())
    }

    @Test
    fun selectionsSurviveNewInstancesAndClearingIsVisibleToExistingInstances() {
        val file = File(temporary.root, "private/selection.json")
        val writer = AppLaunchAllowlistStore(file)
        val reader = AppLaunchAllowlistStore(file)
        val selected = setOf("com.example.notes", "com.android.settings")
        writer.save(selected)
        assertEquals(selected, reader.read())
        assertEquals(selected, AppLaunchAllowlistStore(file).read())
        AppLaunchAllowlistStore(file).save(emptySet())
        assertEquals(emptySet<String>(), writer.read())
        assertEquals(emptySet<String>(), reader.read())
        assertEquals("[]", file.readText())
    }

    @Test
    fun malformedOrUnreadableStorageFailsClosedWithoutKeepingOldSelection() {
        val file = File(temporary.root, "selection.json")
        val store = AppLaunchAllowlistStore(file)
        for (invalid in listOf("", "[", "null", "{}", "[null]", "[1]", "[\"com.example.notes\",\"bad package\"]")) {
            store.save(setOf("com.example.notes"))
            file.writeText(invalid)
            assertEquals(invalid, emptySet<String>(), store.read())
        }
        file.delete()
        file.mkdir()
        assertEquals(emptySet<String>(), store.read())
    }

    @Test
    fun saveFailuresPropagateAndInvalidSelectionDoesNotReplaceSavedData() {
        val file = File(temporary.root, "selection.json")
        val store = AppLaunchAllowlistStore(file)
        store.save(setOf("com.example.notes"))
        val before = file.readText()
        assertThrows(IllegalArgumentException::class.java) { store.save(setOf("invalid package")) }
        assertEquals(before, file.readText())
        val blockedParent = temporary.newFile("not-a-directory")
        assertThrows(IOException::class.java) {
            AppLaunchAllowlistStore(File(blockedParent, "selection.json")).save(emptySet())
        }
    }

    @Test
    fun failedAtomicReplacementCleansTemporaryFileAndPreservesExistingTarget() {
        val target = temporary.newFolder("selection.json")
        val marker = File(target, "keep.txt").apply { writeText("existing") }
        assertThrows(IOException::class.java) { AppLaunchAllowlistStore(target).save(setOf("com.example.notes")) }
        assertEquals("existing", marker.readText())
        assertEquals(listOf("selection.json"), temporary.root.list()?.toList())
    }
}
