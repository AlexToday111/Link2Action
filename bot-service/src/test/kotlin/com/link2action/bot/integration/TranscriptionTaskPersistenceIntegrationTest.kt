package com.link2action.bot.integration

import com.link2action.bot.task.CreateTranscriptionTaskCommand
import com.link2action.bot.task.TranscriptionStatus
import com.link2action.bot.task.TranscriptionTaskRepository
import com.link2action.bot.task.TranscriptionTaskService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TranscriptionTaskPersistenceIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var taskService: TranscriptionTaskService

    @Autowired
    private lateinit var repository: TranscriptionTaskRepository

    @Test
    fun `task is persisted and status updates are stored in postgres`() {
        val taskId = taskService.createTask(
            CreateTranscriptionTaskCommand(
                telegramChatId = 1001,
                telegramUserId = 2001,
                sourceUrl = "https://youtu.be/persistence-${System.nanoTime()}",
                language = "en",
                requestedFormats = setOf("TXT")
            )
        )

        val saved = repository.findById(taskId).orElse(null)
        assertNotNull(saved)
        assertEquals(TranscriptionStatus.QUEUED, saved.status)

        val activeTasks = repository.findByTelegramUserIdAndDeletedAtIsNullAndStatusInOrderByCreatedAtDesc(
            telegramUserId = 2001,
            statuses = setOf(TranscriptionStatus.QUEUED, TranscriptionStatus.PROCESSING)
        )
        assertEquals(taskId, activeTasks.first().id)

        taskService.markProcessing(taskId)
        val processing = repository.findById(taskId).orElseThrow()
        assertEquals(TranscriptionStatus.PROCESSING, processing.status)
        assertNotNull(processing.startedAt)

        taskService.markCompleted(
            taskId = taskId,
            resultTxtPath = "/data/results/$taskId/transcript.txt",
            resultMdPath = "/data/results/$taskId/transcript.md",
            title = "Video title",
            durationSeconds = 120,
            detectedLanguage = "en"
        )

        val completed = repository.findById(taskId).orElseThrow()
        assertEquals(TranscriptionStatus.COMPLETED, completed.status)
        assertEquals("/data/results/$taskId/transcript.txt", completed.resultTxtPath)
        assertEquals("/data/results/$taskId/transcript.md", completed.resultMdPath)
        assertEquals("Video title", completed.title)
        assertEquals(120, completed.durationSeconds)

        val failedTaskId = taskService.createTask(
            CreateTranscriptionTaskCommand(
                telegramChatId = 1001,
                telegramUserId = 2001,
                sourceUrl = "https://youtu.be/failed-${System.nanoTime()}",
                language = "en",
                requestedFormats = setOf("MD")
            )
        )

        taskService.markFailed(failedTaskId, "worker failed")

        val failed = repository.findById(failedTaskId).orElseThrow()
        assertEquals(TranscriptionStatus.FAILED, failed.status)
        assertEquals("worker failed", failed.errorMessage)
    }
}
