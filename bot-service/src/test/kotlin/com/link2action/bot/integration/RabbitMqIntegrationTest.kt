package com.link2action.bot.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.link2action.bot.config.AppProperties
import com.link2action.bot.queue.TranscriptionRequestedEvent
import com.link2action.bot.queue.TranscriptionResultEvent
import com.link2action.bot.queue.TranscriptionResultStatus
import com.link2action.bot.task.CreateTranscriptionTaskCommand
import com.link2action.bot.task.TranscriptionSourceType
import com.link2action.bot.task.TranscriptionStatus
import com.link2action.bot.task.TranscriptionTaskRepository
import com.link2action.bot.task.TranscriptionTaskService
import com.link2action.bot.telegram.TelegramMessageSender
import org.junit.jupiter.api.Test
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.mock.mockito.MockBean
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RabbitMqIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var taskService: TranscriptionTaskService

    @Autowired
    private lateinit var repository: TranscriptionTaskRepository

    @Autowired
    private lateinit var rabbitTemplate: RabbitTemplate

    @Autowired
    private lateinit var appProperties: AppProperties

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockBean
    private lateinit var telegramMessageSender: TelegramMessageSender

    @Test
    fun `creating task publishes request event to rabbitmq`() {
        purgeQueue(appProperties.rabbit.requestQueue)

        val taskId = taskService.createTask(
            CreateTranscriptionTaskCommand(
                telegramChatId = 5001,
                telegramUserId = 6001,
                sourceUrl = "https://youtu.be/rabbit-publish-${System.nanoTime()}",
                language = "en",
                requestedFormats = setOf("TXT", "MD")
            )
        )

        val message = rabbitTemplate.receive(appProperties.rabbit.requestQueue, 5000)

        assertNotNull(message)
        val event = objectMapper.readValue(message.body, TranscriptionRequestedEvent::class.java)
        assertEquals(taskId, event.taskId)
        assertEquals(TranscriptionSourceType.URL, event.sourceType)
        assertTrue(event.sourceUrl.orEmpty().startsWith("https://youtu.be/rabbit-publish-"))
        assertEquals("en", event.language)
        assertEquals(listOf("TXT", "MD"), event.formats)
        assertNotNull(event.createdAt)
    }

    @Test
    fun `creating telegram file task publishes source metadata to rabbitmq`() {
        purgeQueue(appProperties.rabbit.requestQueue)

        val taskId = taskService.createTask(
            CreateTranscriptionTaskCommand(
                telegramChatId = 5101,
                telegramUserId = 6101,
                sourceType = TranscriptionSourceType.TELEGRAM_FILE,
                telegramFileId = "telegram-file-id-${System.nanoTime()}",
                telegramFileUniqueId = "telegram-file-unique-id",
                originalFileName = "lecture.mp4",
                mimeType = "video/mp4",
                fileSizeBytes = 18_400_000,
                language = null,
                requestedFormats = setOf("TXT", "MD")
            )
        )

        val message = rabbitTemplate.receive(appProperties.rabbit.requestQueue, 5000)

        assertNotNull(message)
        val event = objectMapper.readValue(message.body, TranscriptionRequestedEvent::class.java)
        assertEquals(taskId, event.taskId)
        assertEquals(TranscriptionSourceType.TELEGRAM_FILE, event.sourceType)
        assertEquals(null, event.sourceUrl)
        assertEquals("telegram-file-unique-id", event.telegramFileUniqueId)
        assertEquals("lecture.mp4", event.originalFileName)
        assertEquals("video/mp4", event.mimeType)
        assertEquals(18_400_000, event.fileSizeBytes)
    }

    @Test
    fun `completed result event updates task in database`() {
        val taskId = taskService.createTask(
            CreateTranscriptionTaskCommand(
                telegramChatId = 7001,
                telegramUserId = 8001,
                sourceUrl = "https://youtu.be/result-completed-${System.nanoTime()}",
                language = "en",
                requestedFormats = setOf("TXT")
            )
        )

        rabbitTemplate.convertAndSend(
            appProperties.rabbit.exchange,
            appProperties.rabbit.completedRoutingKey,
            TranscriptionResultEvent(
                taskId = taskId,
                status = TranscriptionResultStatus.COMPLETED,
                title = "Video title",
                durationSeconds = 120,
                language = "en",
                resultTxtPath = "/data/results/$taskId/transcript.txt",
                resultMdPath = null,
                errorMessage = null,
                completedAt = Instant.now()
            )
        )

        val completed = waitForTaskStatus(taskId, TranscriptionStatus.COMPLETED)

        assertEquals("/data/results/$taskId/transcript.txt", completed.resultTxtPath)
        assertEquals("Video title", completed.title)
        assertEquals(120, completed.durationSeconds)
    }

    @Test
    fun `failed result event stores error message`() {
        val taskId = taskService.createTask(
            CreateTranscriptionTaskCommand(
                telegramChatId = 9001,
                telegramUserId = 10001,
                sourceUrl = "https://youtu.be/result-failed-${System.nanoTime()}",
                language = "en",
                requestedFormats = setOf("MD")
            )
        )

        rabbitTemplate.convertAndSend(
            appProperties.rabbit.exchange,
            appProperties.rabbit.failedRoutingKey,
            TranscriptionResultEvent(
                taskId = taskId,
                status = TranscriptionResultStatus.FAILED,
                title = null,
                durationSeconds = null,
                language = null,
                resultTxtPath = null,
                resultMdPath = null,
                errorMessage = "worker failed",
                completedAt = Instant.now()
            )
        )

        val failed = waitForTaskStatus(taskId, TranscriptionStatus.FAILED)

        assertEquals("worker failed", failed.errorMessage)
    }

    private fun purgeQueue(queue: String) {
        rabbitTemplate.execute { channel ->
            channel.queuePurge(queue)
            null
        }
    }

    private fun waitForTaskStatus(
        taskId: java.util.UUID,
        status: TranscriptionStatus
    ): com.link2action.bot.task.TranscriptionTask {
        val deadline = System.currentTimeMillis() + 5000

        while (System.currentTimeMillis() < deadline) {
            val task = repository.findById(taskId).orElseThrow()
            if (task.status == status) {
                return task
            }
            Thread.sleep(100)
        }

        throw AssertionError("Task $taskId did not reach status $status")
    }
}
