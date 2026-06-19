package com.link2action.bot.task

import com.link2action.bot.common.ClockProvider
import com.link2action.bot.config.AppProperties
import com.link2action.bot.queue.TranscriptionRequestPublisher
import com.link2action.bot.queue.TranscriptionRequestedEvent
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class TranscriptionTaskServiceTest {

    private lateinit var repository: TranscriptionTaskRepository
    private lateinit var publisher: TranscriptionRequestPublisher
    private lateinit var rabbitTemplate: RabbitTemplate
    private lateinit var clockProvider: ClockProvider
    private lateinit var service: TranscriptionTaskService
    private lateinit var appProperties: AppProperties

    private val now = Instant.parse("2026-06-18T15:30:00Z")

    @BeforeEach
    fun setUp() {
        repository = Mockito.mock(TranscriptionTaskRepository::class.java)
        rabbitTemplate = Mockito.mock(RabbitTemplate::class.java)
        clockProvider = Mockito.mock(ClockProvider::class.java)
        appProperties = appProperties()
        publisher = TranscriptionRequestPublisher(rabbitTemplate, appProperties)

        Mockito.`when`(clockProvider.now()).thenReturn(now)
        Mockito.`when`(
            repository.countByTelegramUserIdAndDeletedAtIsNullAndStatusIn(
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyCollection()
            )
        ).thenReturn(0)
        Mockito.`when`(
            repository.save(ArgumentMatchers.any(TranscriptionTask::class.java))
        ).thenAnswer { invocation -> invocation.arguments[0] }

        service = TranscriptionTaskService(
            repository = repository,
            requestPublisher = publisher,
            appProperties = appProperties,
            clockProvider = clockProvider
        )
    }

    @Test
    fun `creating first task persists and publishes request`() {
        Mockito.`when`(
            repository.findFirstByIdempotencyKeyAndDeletedAtIsNullAndStatusInOrderByCreatedAtDesc(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyCollection()
            )
        ).thenReturn(null)

        val taskId = service.createTask(command(requestedFormats = setOf("TXT", "MD")))

        val taskCaptor = ArgumentCaptor.forClass(TranscriptionTask::class.java)
        Mockito.verify(repository).save(taskCaptor.capture())
        val savedTask = taskCaptor.value

        assertEquals(savedTask.id, taskId)
        assertEquals(TranscriptionStatus.QUEUED, savedTask.status)
        assertEquals("TXT,MD", savedTask.requestedFormat)
        assertNotNull(savedTask.idempotencyKey)

        val eventCaptor = ArgumentCaptor.forClass(Any::class.java)
        Mockito.verify(rabbitTemplate).convertAndSend(
            ArgumentMatchers.eq("linkscribe.exchange"),
            ArgumentMatchers.eq("transcription.requested"),
            eventCaptor.capture()
        )
        val event = eventCaptor.value as TranscriptionRequestedEvent
        assertEquals(taskId, event.taskId)
        assertEquals(listOf("TXT", "MD"), event.formats)
    }

    @Test
    fun `repeated request with same parameters returns existing active task`() {
        val existingTask = task(id = UUID.fromString("11111111-1111-1111-1111-111111111111"))
        Mockito.`when`(
            repository.findFirstByIdempotencyKeyAndDeletedAtIsNullAndStatusInOrderByCreatedAtDesc(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyCollection()
            )
        ).thenReturn(existingTask)

        val taskId = service.createTask(command())

        assertEquals(existingTask.id, taskId)
        Mockito.verify(repository, Mockito.never()).save(ArgumentMatchers.any(TranscriptionTask::class.java))
        Mockito.verify(rabbitTemplate, Mockito.never()).convertAndSend(
            ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString(),
            ArgumentMatchers.any(Any::class.java)
        )
    }

    @Test
    fun `completed task does not block creating another task for same parameters`() {
        Mockito.`when`(
            repository.findFirstByIdempotencyKeyAndDeletedAtIsNullAndStatusInOrderByCreatedAtDesc(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyCollection()
            )
        ).thenReturn(null)

        val taskId = service.createTask(command())

        assertNotNull(taskId)
        Mockito.verify(repository).save(ArgumentMatchers.any(TranscriptionTask::class.java))
        Mockito.verify(rabbitTemplate).convertAndSend(
            ArgumentMatchers.eq("linkscribe.exchange"),
            ArgumentMatchers.eq("transcription.requested"),
            ArgumentMatchers.any(Any::class.java)
        )
    }

    @Test
    fun `different formats or language create different idempotency keys`() {
        Mockito.`when`(
            repository.findFirstByIdempotencyKeyAndDeletedAtIsNullAndStatusInOrderByCreatedAtDesc(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyCollection()
            )
        ).thenReturn(null)

        service.createTask(command(requestedFormats = setOf("TXT"), language = "en"))
        service.createTask(command(requestedFormats = setOf("MD"), language = "en"))
        service.createTask(command(requestedFormats = setOf("TXT"), language = "ru"))

        val taskCaptor = ArgumentCaptor.forClass(TranscriptionTask::class.java)
        Mockito.verify(repository, Mockito.times(3)).save(taskCaptor.capture())
        val keys = taskCaptor.allValues.map { it.idempotencyKey }

        assertEquals(3, keys.toSet().size)
        assertNotEquals(keys[0], keys[1])
        assertNotEquals(keys[0], keys[2])
    }

    private fun command(
        requestedFormats: Set<String> = setOf("TXT", "MD"),
        language: String? = "EN"
    ): CreateTranscriptionTaskCommand {
        return CreateTranscriptionTaskCommand(
            telegramChatId = 100,
            telegramUserId = 200,
            sourceUrl = " https://youtu.be/example ",
            language = language,
            requestedFormats = requestedFormats
        )
    }

    private fun task(
        id: UUID = UUID.randomUUID(),
        status: TranscriptionStatus = TranscriptionStatus.QUEUED
    ): TranscriptionTask {
        return TranscriptionTask(
            id = id,
            telegramChatId = 100,
            telegramUserId = 200,
            sourceUrl = "https://youtu.be/example",
            status = status,
            requestedFormat = "TXT,MD",
            language = "en",
            idempotencyKey = "existing-key",
            createdAt = now,
            updatedAt = now
        )
    }

    private fun appProperties(): AppProperties {
        return AppProperties(
            telegram = AppProperties.Telegram(
                botToken = "token",
                botUsername = "Link2ActionBot"
            ),
            rabbit = AppProperties.Rabbit(
                exchange = "linkscribe.exchange",
                requestQueue = "linkscribe.transcription.requests",
                resultQueue = "linkscribe.transcription.results",
                requestRoutingKey = "transcription.requested",
                completedRoutingKey = "transcription.completed",
                failedRoutingKey = "transcription.failed",
                progressRoutingKey = "transcription.progress"
            ),
            storage = AppProperties.Storage(resultsBasePath = "/tmp/results"),
            transcription = AppProperties.Transcription(maxActiveTasksPerUser = 10)
        )
    }
}
