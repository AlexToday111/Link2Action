package com.link2action.bot.integration

import com.link2action.bot.task.CreateTranscriptionTaskCommand
import com.link2action.bot.task.TranscriptionTaskRepository
import com.link2action.bot.task.TranscriptionTaskService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class TranscriptionTaskIdempotencyIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var taskService: TranscriptionTaskService

    @Autowired
    private lateinit var repository: TranscriptionTaskRepository

    @Test
    fun `duplicate active task returns existing task and completed task allows a new one`() {
        val sourceUrl = " https://youtu.be/idempotency-${System.nanoTime()} "
        val command = command(sourceUrl = sourceUrl, language = " EN ", requestedFormats = setOf("TXT", "MD"))

        val firstTaskId = taskService.createTask(command)
        val secondTaskId = taskService.createTask(command)

        assertEquals(firstTaskId, secondTaskId)

        taskService.markCompleted(
            taskId = firstTaskId,
            resultTxtPath = "/data/results/$firstTaskId/transcript.txt",
            resultMdPath = "/data/results/$firstTaskId/transcript.md",
            title = "Video title",
            durationSeconds = 120,
            detectedLanguage = "en"
        )

        val thirdTaskId = taskService.createTask(command)

        assertNotEquals(firstTaskId, thirdTaskId)
    }

    @Test
    fun `different language format or source url create different idempotency keys`() {
        val baseUrl = "https://youtu.be/idempotency-variants-${System.nanoTime()}"

        val txtEn = taskService.createTask(command(sourceUrl = baseUrl, language = "en", requestedFormats = setOf("TXT")))
        val mdEn = taskService.createTask(command(sourceUrl = baseUrl, language = "en", requestedFormats = setOf("MD")))
        val txtRu = taskService.createTask(command(sourceUrl = baseUrl, language = "ru", requestedFormats = setOf("TXT")))
        val otherSource = taskService.createTask(
            command(
                sourceUrl = "$baseUrl-other",
                language = "en",
                requestedFormats = setOf("TXT")
            )
        )

        val tasks = listOf(txtEn, mdEn, txtRu, otherSource)
            .map { repository.findById(it).orElseThrow() }

        assertEquals(4, tasks.map { it.idempotencyKey }.toSet().size)
        tasks.forEach { task ->
            assertNotNull(task.idempotencyKey)
        }
    }

    private fun command(
        sourceUrl: String,
        language: String?,
        requestedFormats: Set<String>
    ): CreateTranscriptionTaskCommand {
        return CreateTranscriptionTaskCommand(
            telegramChatId = 3001,
            telegramUserId = 4001,
            sourceUrl = sourceUrl,
            language = language,
            requestedFormats = requestedFormats
        )
    }
}
