package com.link2action.bot.telegram

import com.link2action.bot.artifact.TranscriptionTaskArtifactService
import com.link2action.bot.config.AppProperties
import com.link2action.bot.storage.StorageCleanupService
import com.link2action.bot.task.CreateTranscriptionTaskCommand
import com.link2action.bot.task.ProcessingMode
import com.link2action.bot.task.TranscriptionSourceType
import com.link2action.bot.task.TranscriptionTaskService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramCommandRouterTest {

    private lateinit var taskService: TranscriptionTaskService
    private lateinit var storageCleanupService: StorageCleanupService
    private lateinit var artifactService: TranscriptionTaskArtifactService
    private lateinit var messageSender: TelegramMessageSender
    private lateinit var router: TelegramCommandRouter

    @BeforeEach
    fun setUp() {
        taskService = Mockito.mock(
            TranscriptionTaskService::class.java,
            Mockito.withSettings().defaultAnswer { invocation ->
                when (invocation.method.name) {
                    "createWaitingFormatTask" -> UUID.fromString("11111111-1111-1111-1111-111111111111")
                    "createTask" -> UUID.fromString("22222222-2222-2222-2222-222222222222")
                    else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
                }
            }
        )
        storageCleanupService = Mockito.mock(StorageCleanupService::class.java)
        artifactService = Mockito.mock(TranscriptionTaskArtifactService::class.java)
        messageSender = Mockito.mock(TelegramMessageSender::class.java)
        router = router(appProperties())
    }

    @Test
    fun `url source task creation still works`() {
        router.route(message(text = "https://youtu.be/example"))

        val command = captureWaitingCommand()

        assertEquals(TranscriptionSourceType.URL, command.sourceType)
        assertEquals("https://youtu.be/example", command.sourceUrl)
        assertNull(command.telegramFileId)
        Mockito.verify(messageSender).sendText(
            ArgumentMatchers.eq(CHAT_ID),
            ArgumentMatchers.contains("Ссылка получена"),
            ArgumentMatchers.argThat { markup -> markup.toString().contains("callback_data=m:") },
            ArgumentMatchers.eq(true)
        )
    }

    @Test
    fun `processing mode keyboard callback data fits Telegram limit`() {
        router.route(message(text = "https://youtu.be/example"))

        val callbackData = sentReplyMarkupCallbackData()

        assertTrue(callbackData.isNotEmpty())
        callbackData.forEach { data ->
            assertTrue(
                data.toByteArray(Charsets.UTF_8).size <= 64,
                "callback_data is too long for Telegram: $data"
            )
        }
        assertTrue(callbackData.any { it.endsWith(":CR") })
    }

    @Test
    fun `failed processing mode prompt delivery cancels waiting task`() {
        val taskId = UUID.fromString("11111111-1111-1111-1111-111111111111")

        router.route(message(text = "https://youtu.be/example"))

        Mockito.verify(taskService).cancelWaitingTask(taskId, USER_ID)
    }

    @Test
    fun `content repurpose format keyboard callback data fits Telegram limit`() {
        val taskId = "11111111-1111-1111-1111-111111111111"

        router.routeCallback(callback("m:$taskId:CR"))

        val callbackData = editedReplyMarkupCallbackData()

        assertTrue(callbackData.isNotEmpty())
        callbackData.forEach { data ->
            assertTrue(
                data.toByteArray(Charsets.UTF_8).size <= 64,
                "callback_data is too long for Telegram: $data"
            )
        }
        assertTrue(callbackData.any { it == "f:$taskId:CR:PKG" })
    }

    @Test
    fun `telegram video message creates telegram file pending task`() {
        router.route(
            message(
                video = TelegramVideo(
                    fileId = "video-file-id",
                    fileUniqueId = "video-unique-id",
                    fileName = "lecture.mp4",
                    mimeType = "video/mp4",
                    fileSize = 18_400_000
                )
            )
        )

        val command = captureWaitingCommand()

        assertEquals(TranscriptionSourceType.TELEGRAM_FILE, command.sourceType)
        assertEquals("video-file-id", command.telegramFileId)
        assertEquals("video-unique-id", command.telegramFileUniqueId)
        assertEquals("lecture.mp4", command.originalFileName)
        assertEquals("video/mp4", command.mimeType)
        assertEquals(18_400_000, command.fileSizeBytes)
    }

    @Test
    fun `telegram audio message creates telegram file pending task`() {
        router.route(
            message(
                audio = TelegramAudio(
                    fileId = "audio-file-id",
                    fileUniqueId = "audio-unique-id",
                    fileName = "lecture.mp3",
                    mimeType = "audio/mpeg",
                    fileSize = 10_000
                )
            )
        )

        val command = captureWaitingCommand()

        assertEquals(TranscriptionSourceType.TELEGRAM_FILE, command.sourceType)
        assertEquals("audio-file-id", command.telegramFileId)
        assertEquals("lecture.mp3", command.originalFileName)
    }

    @Test
    fun `voice message creates telegram file pending task`() {
        router.route(
            message(
                voice = TelegramVoice(
                    fileId = "voice-file-id",
                    fileUniqueId = "voice-unique-id",
                    mimeType = "audio/ogg",
                    fileSize = 4096
                )
            )
        )

        val command = captureWaitingCommand()

        assertEquals(TranscriptionSourceType.TELEGRAM_FILE, command.sourceType)
        assertEquals("voice-file-id", command.telegramFileId)
        assertEquals("voice.ogg", command.originalFileName)
    }

    @Test
    fun `unsupported document returns error without creating task`() {
        router.route(
            message(
                document = TelegramDocument(
                    fileId = "doc-file-id",
                    fileUniqueId = "doc-unique-id",
                    fileName = "notes.pdf",
                    mimeType = "application/pdf",
                    fileSize = 4096
                )
            )
        )

        assertEquals(0, waitingTaskCommands().size)
        Mockito.verify(messageSender).sendText(
            ArgumentMatchers.eq(CHAT_ID),
            ArgumentMatchers.contains("Этот тип файла пока не поддерживается"),
            ArgumentMatchers.isNull(),
            ArgumentMatchers.eq(false)
        )
    }

    @Test
    fun `too large file returns error without creating task`() {
        router.route(
            message(
                video = TelegramVideo(
                    fileId = "video-file-id",
                    fileUniqueId = "video-unique-id",
                    fileName = "large.mp4",
                    mimeType = "video/mp4",
                    fileSize = 25_000_000
                )
            )
        )

        assertEquals(0, waitingTaskCommands().size)
        Mockito.verify(messageSender).sendText(
            ArgumentMatchers.eq(CHAT_ID),
            ArgumentMatchers.contains("Файл слишком большой"),
            ArgumentMatchers.isNull(),
            ArgumentMatchers.eq(false)
        )
    }

    @Test
    fun `batch mode collects sources and creates tasks after format selection`() {
        router.route(message(text = "/batch"))
        router.route(message(text = "https://youtu.be/one"))
        router.route(message(voice = TelegramVoice(fileId = "voice-file-id", fileUniqueId = "voice-unique-id")))
        router.route(message(text = "/done"))
        router.routeCallback(callback("batch_mode_select:ACTION_ITEMS"))
        router.routeCallback(callback("batch_format_select:BOTH"))

        val commands = createdTaskCommands()
        assertEquals(2, commands.size)
        assertEquals(TranscriptionSourceType.URL, commands[0].sourceType)
        assertEquals(TranscriptionSourceType.TELEGRAM_FILE, commands[1].sourceType)
        assertEquals(setOf("TXT", "MD"), commands[0].requestedFormats)
        assertEquals(setOf("TXT", "MD"), commands[1].requestedFormats)
        assertEquals(ProcessingMode.ACTION_ITEMS, commands[0].processingMode)
        assertEquals(ProcessingMode.ACTION_ITEMS, commands[1].processingMode)
    }

    @Test
    fun `cancel clears batch state`() {
        router.route(message(text = "/batch"))
        router.route(message(text = "https://youtu.be/one"))
        router.route(message(text = "/cancel"))
        router.route(message(text = "/done"))

        assertEquals(0, createdTaskCommands().size)
        Mockito.verify(messageSender).sendText(
            ArgumentMatchers.eq(CHAT_ID),
            ArgumentMatchers.contains("Batch пуст"),
            ArgumentMatchers.isNull(),
            ArgumentMatchers.eq(false)
        )
    }

    @Test
    fun `batch max size is enforced`() {
        router = router(appProperties(maxBatchSize = 1))
        router.route(message(text = "/batch"))
        router.route(message(text = "https://youtu.be/one"))
        router.route(message(text = "https://youtu.be/two"))

        Mockito.verify(messageSender).sendText(
            ArgumentMatchers.eq(CHAT_ID),
            ArgumentMatchers.contains("максимум 1 источников"),
            ArgumentMatchers.isNull(),
            ArgumentMatchers.eq(false)
        )
    }

    private fun captureWaitingCommand(): CreateTranscriptionTaskCommand {
        val commands = waitingTaskCommands()
        assertEquals(1, commands.size)
        return commands.single()
    }

    private fun waitingTaskCommands(): List<CreateTranscriptionTaskCommand> {
        return Mockito.mockingDetails(taskService)
            .invocations
            .filter { it.method.name == "createWaitingFormatTask" }
            .map { it.arguments[0] as CreateTranscriptionTaskCommand }
    }

    private fun createdTaskCommands(): List<CreateTranscriptionTaskCommand> {
        return Mockito.mockingDetails(taskService)
            .invocations
            .filter { it.method.name == "createTask" }
            .map { it.arguments[0] as CreateTranscriptionTaskCommand }
    }

    private fun sentReplyMarkupCallbackData(): List<String> {
        val replyMarkup = Mockito.mockingDetails(messageSender)
            .invocations
            .filter { it.method.name == "sendText" }
            .mapNotNull { it.arguments.getOrNull(2) as? Map<*, *> }
            .lastOrNull()
            ?: return emptyList()

        return callbackDataFromReplyMarkup(replyMarkup)
    }

    private fun editedReplyMarkupCallbackData(): List<String> {
        val replyMarkup = Mockito.mockingDetails(messageSender)
            .invocations
            .filter { it.method.name == "editMessageText" }
            .mapNotNull { it.arguments.getOrNull(3) as? Map<*, *> }
            .lastOrNull()
            ?: return emptyList()

        return callbackDataFromReplyMarkup(replyMarkup)
    }

    private fun callbackDataFromReplyMarkup(replyMarkup: Map<*, *>): List<String> {
        val rows = replyMarkup["inline_keyboard"] as? List<*>
            ?: return emptyList()

        return rows
            .flatMap { row -> row as? List<*> ?: emptyList<Any>() }
            .mapNotNull { button -> (button as? Map<*, *>)?.get("callback_data") as? String }
    }

    private fun router(appProperties: AppProperties): TelegramCommandRouter {
        return TelegramCommandRouter(
            taskService = taskService,
            storageCleanupService = storageCleanupService,
            artifactService = artifactService,
            messageSender = messageSender,
            appProperties = appProperties
        )
    }

    private fun message(
        text: String? = null,
        video: TelegramVideo? = null,
        document: TelegramDocument? = null,
        audio: TelegramAudio? = null,
        voice: TelegramVoice? = null,
        videoNote: TelegramVideoNote? = null
    ): TelegramMessage {
        return TelegramMessage(
            messageId = 100,
            chat = TelegramChat(CHAT_ID),
            from = TelegramUser(USER_ID),
            text = text,
            video = video,
            document = document,
            audio = audio,
            voice = voice,
            videoNote = videoNote
        )
    }

    private fun callback(data: String): TelegramCallbackQuery {
        return TelegramCallbackQuery(
            id = "callback-id",
            from = TelegramUser(USER_ID),
            message = message(),
            data = data
        )
    }

    private fun appProperties(maxBatchSize: Int = 5): AppProperties {
        return AppProperties(
            telegram = AppProperties.Telegram(
                botToken = "token",
                botUsername = "Link2ActionBot",
                maxUploadFileSizeBytes = 20_971_520
            ),
            rabbit = AppProperties.Rabbit(
                exchange = "linkscribe.exchange",
                requestQueue = "linkscribe.transcription.requests",
                requestRetryQueue = "linkscribe.transcription.requests.retry",
                requestDlq = "linkscribe.transcription.requests.dlq",
                resultQueue = "linkscribe.transcription.results",
                requestRoutingKey = "transcription.requested",
                retryRoutingKey = "transcription.requested.retry",
                dlqRoutingKey = "transcription.requested.dlq",
                completedRoutingKey = "transcription.completed",
                failedRoutingKey = "transcription.failed",
                progressRoutingKey = "transcription.progress",
                retryDelayMs = 30000
            ),
            storage = AppProperties.Storage(resultsBasePath = "/tmp/results"),
            transcription = AppProperties.Transcription(
                maxActiveTasksPerUser = 10,
                maxBatchSize = maxBatchSize
            )
        )
    }

    private companion object {
        const val CHAT_ID = 1000L
        const val USER_ID = 2000L
    }
}
