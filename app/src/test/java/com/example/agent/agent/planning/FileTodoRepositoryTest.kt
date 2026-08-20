package com.example.agent.agent.planning

import com.example.agent.agent.model.CreateTodo
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FileTodoRepositoryTest {
    @Test
    fun todos_surviveRepositoryRecreation() = runTest {
        val storageFile = File.createTempFile("agent-todos", ".json")
        storageFile.delete()
        try {
            val todo = CreateTodo("投递 Android 岗位", dueAt = "2026-08-21T09:00:00+08:00")
            FileTodoRepository(storageFile).add(todo)

            assertEquals(
                listOf(todo),
                FileTodoRepository(storageFile).list(),
            )
        } finally {
            storageFile.delete()
        }
    }
}
