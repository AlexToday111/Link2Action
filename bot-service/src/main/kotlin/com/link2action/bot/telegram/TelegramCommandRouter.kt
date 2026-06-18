package com.link2action.bot.telegram

import com.link2action.bot.artifact.ArtifactType
import com.link2action.bot.artifact.TranscriptionTaskArtifactService
import com.link2action.bot.common.UrlExtractor
import com.link2action.bot.storage.StorageCleanupService
import com.link2action.bot.task.ActiveTaskLimitExceededException
import com.link2action.bot.task.CreateTranscriptionTaskCommand
import com.link2action.bot.task.TranscriptionStatus
import com.link2action.bot.task.TranscriptionTask
import com.link2action.bot.task.TranscriptionTaskService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Component
class TelegramCommandRouter(
    private val taskService: TranscriptionTaskService,
    private val storageCleanupService: StorageCleanupService,
    private val artifactService: TranscriptionTaskArtifactService,
    private val messageSender: TelegramMessageSender
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        .withZone(ZoneId.systemDefault())

    fun route(message: TelegramMessage) {
        val chatId = message.chat.id
        val userId = message.from?.id

        if (userId == null) {
            messageSender.sendText(
                chatId = chatId,
                text = "Не получилось определить пользователя. Попробуй отправить сообщение ещё раз."
            )
            return
        }

        val text = message.text?.trim()

        if (text.isNullOrBlank()) {
            messageSender.sendText(
                chatId = chatId,
                text = "Пока я умею работать только с текстовыми ссылками на видео."
            )
            return
        }

        try {
            when {
                isCommand(text, "/start") -> showMainMenu(chatId)
                isCommand(text, "/help") -> showHelp(chatId, null)
                isCommand(text, "/history") -> showHistory(chatId, userId, null)
                isCommand(text, "/clear") -> showClearStorageRequest(chatId, null)
                isCommand(text, "/get") -> handleGetCommand(chatId, userId, text)
                isCommand(text, "/status") -> handleStatus(chatId, userId, text)
                UrlExtractor.extract(text) != null -> handleVideoUrl(chatId, userId, text)
                else -> handleUnknownMessage(chatId)
            }
        } catch (ex: Exception) {
            log.error("Failed to route Telegram message: chatId={}, userId={}", chatId, userId, ex)
            messageSender.sendText(
                chatId = chatId,
                text = "Что-то пошло не так. Попробуй позже."
            )
        }
    }

    fun routeCallback(callbackQuery: TelegramCallbackQuery) {
        val data = callbackQuery.data.orEmpty()
        val message = callbackQuery.message
        val chatId = message?.chat?.id
        val messageId = message?.messageId
        val userId = callbackQuery.from.id

        if (chatId == null) {
            log.warn("Skipping callback without message chat: callbackQueryId={}", callbackQuery.id)
            messageSender.answerCallbackQuery(callbackQuery.id)
            return
        }

        val context = CallbackContext(
            chatId = chatId,
            messageId = messageId,
            userId = userId
        )

        try {
            when {
                data == CALLBACK_MENU -> showMainMenu(chatId, messageId)
                data == CALLBACK_HISTORY -> showHistory(chatId, userId, messageId)
                data == CALLBACK_STATUS_ACTIVE -> showActiveStatus(chatId, userId, messageId)
                data == CALLBACK_CLEAR_STORAGE_REQUEST || data == CALLBACK_CLEAN_STORAGE_REQUEST -> {
                    showClearStorageRequest(chatId, messageId)
                }

                data == CALLBACK_CLEAR_STORAGE_CONFIRM || data == CALLBACK_CLEAN_STORAGE_CONFIRM -> {
                    handleClearStorageConfirm(context)
                }

                data == CALLBACK_CLEAR_STORAGE_CANCEL || data == CALLBACK_CLEAN_STORAGE_CANCEL -> {
                    render(
                        context = context,
                        text = "Очистка отменена.",
                        replyMarkup = menuOnlyKeyboard()
                    )
                }

                data == CALLBACK_HELP -> showHelp(chatId, messageId)
                data.startsWith("$CALLBACK_TASK_DETAIL:") -> showTaskDetail(context, data)
                data.startsWith("$CALLBACK_TASK_OPEN:") -> showTaskDetail(context, data)
                data.startsWith("$CALLBACK_GET_RESULT:") -> handleGetCallback(context, data)
                data.startsWith("$CALLBACK_GET_TXT:") -> handleGetCallback(context, data)
                data.startsWith("$CALLBACK_GET_MD:") -> handleGetCallback(context, data)
                data.startsWith("$CALLBACK_DELETE_FILES_REQUEST:") -> handleDeleteFilesRequest(context, data)
                data.startsWith("$CALLBACK_DELETE_FILES_CONFIRM:") -> handleDeleteFilesConfirm(context, data)
                data.startsWith("$CALLBACK_DELETE_FILES_CANCEL:") -> showTaskDetail(context, data, fallbackToHistory = true)
                data.startsWith("$CALLBACK_DELETE_HISTORY_REQUEST:") -> handleDeleteHistoryRequest(context, data)
                data.startsWith("$CALLBACK_DELETE_HISTORY_CONFIRM:") -> handleDeleteHistoryConfirm(context, data)
                data.startsWith("$CALLBACK_DELETE_HISTORY_CANCEL:") -> showTaskDetail(context, data, fallbackToHistory = true)
                data.startsWith("$CALLBACK_OLD_DELETE_TASK_REQUEST:") -> handleDeleteFilesRequest(context, data)
                data.startsWith("$CALLBACK_OLD_DELETE_TASK_CONFIRM:") -> handleDeleteFilesConfirm(context, data)
                data.startsWith("$CALLBACK_OLD_DELETE_TASK_CANCEL:") -> showTaskDetail(context, data, fallbackToHistory = true)
                data.startsWith("$CALLBACK_REPEAT_TASK:") -> handleRepeatTask(context, data)
                data.startsWith("$CALLBACK_FORMAT_SELECT:") -> handleFormatSelect(context, data)
                data.startsWith("$CALLBACK_FORMAT_CANCEL:") -> handleFormatCancel(context, data)
                else -> {
                    log.warn(
                        "Unsupported Telegram callback data: callbackQueryId={}, data={}",
                        callbackQuery.id,
                        data
                    )
                    render(
                        context = context,
                        text = "Неизвестное действие.",
                        replyMarkup = menuOnlyKeyboard()
                    )
                }
            }
        } catch (ex: Exception) {
            log.error("Failed to route Telegram callback: chatId={}, userId={}", chatId, userId, ex)
            render(
                context = context,
                text = "Что-то пошло не так. Попробуй позже.",
                replyMarkup = menuOnlyKeyboard()
            )
        } finally {
            messageSender.answerCallbackQuery(callbackQuery.id)
        }
    }

    private fun showMainMenu(
        chatId: Long,
        messageId: Long? = null
    ) {
        render(
            context = CallbackContext(chatId = chatId, messageId = messageId, userId = null),
            text = mainMenuText(),
            replyMarkup = startKeyboard()
        )
    }

    private fun showHelp(
        chatId: Long,
        messageId: Long?
    ) {
        render(
            context = CallbackContext(chatId = chatId, messageId = messageId, userId = null),
            text = """
                Как пользоваться:

                1. Скопируй ссылку на видео.
                2. Отправь её сюда обычным сообщением.
                3. Выбери формат результата.
                4. Я покажу прогресс обработки.
                5. После обработки пришлю выбранные файлы.

                Команды:
                /history — история задач
                /status — активные задачи
                /status <taskId> — статус конкретной задачи
                /get <taskId> — скачать готовый результат ещё раз
                /clear — очистить свои файлы
            """.trimIndent(),
            replyMarkup = menuOnlyKeyboard()
        )
    }

    private fun showHistory(
        chatId: Long,
        userId: Long,
        messageId: Long?
    ) {
        val tasks = taskService.getLatestUserTasks(userId, HISTORY_LIMIT)
        val context = CallbackContext(chatId = chatId, messageId = messageId, userId = userId)

        if (tasks.isEmpty()) {
            render(
                context = context,
                text = "История задач пока пустая. Пришли ссылку на видео, чтобы создать первую задачу.",
                replyMarkup = menuOnlyKeyboard(),
                disableWebPagePreview = true
            )
            return
        }

        render(
            context = context,
            text = buildHistoryMessage(tasks),
            replyMarkup = historyKeyboard(tasks),
            disableWebPagePreview = true
        )
    }

    private fun handleStatus(
        chatId: Long,
        userId: Long,
        text: String
    ) {
        val rawTaskId = commandArgument(text)

        if (rawTaskId.isBlank()) {
            showActiveStatus(chatId, userId, null)
            return
        }

        val taskId = parseTaskId(rawTaskId) ?: run {
            messageSender.sendText(
                chatId = chatId,
                text = "Некорректный taskId. Он должен быть в формате UUID."
            )
            return
        }

        val task = taskService.getUserTask(taskId, userId)

        if (task == null) {
            messageSender.sendText(chatId = chatId, text = "Задача не найдена.")
            return
        }

        messageSender.sendText(
            chatId = chatId,
            text = buildTaskStatusMessage(task),
            replyMarkup = taskDetailKeyboard(task),
            disableWebPagePreview = true
        )
    }

    private fun showActiveStatus(
        chatId: Long,
        userId: Long,
        messageId: Long?
    ) {
        val tasks = taskService.getActiveUserTasks(userId)
        val context = CallbackContext(chatId = chatId, messageId = messageId, userId = userId)

        if (tasks.isEmpty()) {
            render(
                context = context,
                text = "Сейчас нет активных задач.",
                replyMarkup = menuOnlyKeyboard()
            )
            return
        }

        render(
            context = context,
            text = buildActiveTasksMessage(tasks),
            replyMarkup = activeStatusKeyboard(),
            disableWebPagePreview = true
        )
    }

    private fun handleGetCommand(
        chatId: Long,
        userId: Long,
        text: String
    ) {
        val rawTaskId = commandArgument(text)

        if (rawTaskId.isBlank()) {
            messageSender.sendText(
                chatId = chatId,
                text = "Укажи ID задачи. Пример: /get 7f3a0f72-6e92-4e73-bdf7-efdf90c5aa31"
            )
            return
        }

        val taskId = parseTaskId(rawTaskId) ?: run {
            messageSender.sendText(
                chatId = chatId,
                text = "Некорректный taskId. Он должен быть в формате UUID."
            )
            return
        }

        sendTaskResults(
            context = CallbackContext(chatId = chatId, messageId = null, userId = userId),
            taskId = taskId,
            fileType = ResultFileType.ALL
        )
    }

    private fun handleGetCallback(
        context: CallbackContext,
        data: String
    ) {
        val action = data.substringBefore(":")
        val taskId = parseTaskId(data.substringAfter(":", ""))

        if (taskId == null) {
            render(context, "Задача не найдена.", menuOnlyKeyboard())
            return
        }

        val fileType = when (action) {
            CALLBACK_GET_TXT -> ResultFileType.TXT
            CALLBACK_GET_MD -> ResultFileType.MD
            else -> ResultFileType.ALL
        }

        sendTaskResults(
            context = context,
            taskId = taskId,
            fileType = fileType
        )
    }

    private fun sendTaskResults(
        context: CallbackContext,
        taskId: UUID,
        fileType: ResultFileType
    ) {
        val userId = context.userId ?: return
        val task = taskService.getUserTask(taskId, userId)

        if (task == null) {
            render(context, "Задача не найдена.", menuOnlyKeyboard())
            return
        }

        if (task.status != TranscriptionStatus.COMPLETED) {
            render(
                context = context,
                text = "Задача ещё не завершена. Текущий статус: ${task.status}",
                replyMarkup = taskDetailKeyboard(task)
            )
            return
        }

        val files = resultFiles(task, fileType)
            .filter { Files.isRegularFile(it.path) }

        if (files.isEmpty()) {
            render(
                context = context,
                text = "Файлы результата больше не найдены. Возможно, хранилище было очищено.",
                replyMarkup = taskDetailKeyboard(task)
            )
            return
        }

        files.forEach { resultFile ->
            val sent = messageSender.sendDocument(
                chatId = context.chatId,
                path = resultFile.path,
                caption = resultFile.caption
            )

            if (sent != null) {
                artifactService.recordArtifact(
                    taskId = task.id,
                    telegramChatId = context.chatId,
                    telegramMessageId = sent.messageId,
                    artifactType = resultFile.artifactType,
                    filePath = resultFile.path.toString()
                )
            }
        }
    }

    private fun handleVideoUrl(
        chatId: Long,
        userId: Long,
        text: String
    ) {
        val url = UrlExtractor.extract(text)

        if (url == null) {
            messageSender.sendText(
                chatId = chatId,
                text = "Не получилось извлечь ссылку. Пришли обычную ссылку, начинающуюся с http:// или https://."
            )
            return
        }

        val taskId = try {
            taskService.createWaitingFormatTask(
                CreateTranscriptionTaskCommand(
                    telegramChatId = chatId,
                    telegramUserId = userId,
                    sourceUrl = url
                )
            )
        } catch (ex: ActiveTaskLimitExceededException) {
            messageSender.sendText(
                chatId = chatId,
                text = "У тебя уже есть активная задача. Дождись результата или проверь статус командой /status."
            )
            return
        }

        val sent = messageSender.sendText(
            chatId = chatId,
            text = formatSelectionText(),
            replyMarkup = formatSelectionKeyboard(taskId),
            disableWebPagePreview = true
        )

        if (sent != null) {
            taskService.updateProgressMessageId(taskId, sent.messageId)
        }
    }

    private fun handleFormatSelect(
        context: CallbackContext,
        data: String
    ) {
        val parts = data.split(":")
        if (parts.size != 3) {
            render(context, "Задача не найдена.", menuOnlyKeyboard())
            return
        }

        val taskId = parseTaskId(parts[1])
        val userId = context.userId

        if (taskId == null || userId == null) {
            render(context, "Задача не найдена.", menuOnlyKeyboard())
            return
        }

        if (context.messageId != null) {
            taskService.updateProgressMessageId(taskId, context.messageId)
        }

        val formats = formatsFromSelection(parts[2])

        val result = try {
            taskService.enqueueWaitingTask(
                taskId = taskId,
                telegramUserId = userId,
                requestedFormats = formats
            )
        } catch (ex: ActiveTaskLimitExceededException) {
            render(
                context = context,
                text = "У тебя уже есть активная задача. Дождись результата или проверь статус командой /status.",
                replyMarkup = menuOnlyKeyboard()
            )
            return
        }

        if (result == null) {
            render(context, "Задача не найдена.", menuOnlyKeyboard())
            return
        }

        if (result.alreadySelected) {
            render(
                context = context,
                text = "Формат уже выбран. Задача уже создана.",
                replyMarkup = acceptedTaskKeyboard(),
                disableWebPagePreview = true
            )
            return
        }

        val sent = render(
            context = context,
            text = """
                Задача принята.

                Формат: ${formatLabel(formats)}
                Статус: QUEUED

                Я пришлю результат, когда обработка завершится.
            """.trimIndent(),
            replyMarkup = acceptedTaskKeyboard(),
            disableWebPagePreview = true
        )

        if (sent != null) {
            taskService.updateProgressMessageId(taskId, sent.messageId)
        }
    }

    private fun handleFormatCancel(
        context: CallbackContext,
        data: String
    ) {
        val taskId = parseTaskId(data.substringAfter(":", ""))
        val userId = context.userId

        if (taskId == null || userId == null) {
            render(context, "Задача не найдена.", menuOnlyKeyboard())
            return
        }

        val result = taskService.cancelWaitingTask(taskId, userId)

        if (result == null) {
            render(context, "Задача не найдена.", menuOnlyKeyboard())
            return
        }

        val text = if (result.cancelled) {
            "Задача отменена."
        } else {
            "Формат уже выбран. Задача уже создана."
        }

        render(
            context = context,
            text = text,
            replyMarkup = menuOnlyKeyboard()
        )
    }

    private fun showClearStorageRequest(
        chatId: Long,
        messageId: Long?
    ) {
        render(
            context = CallbackContext(chatId = chatId, messageId = messageId, userId = null),
            text = """
                Ты точно хочешь очистить свои файлы?

                Будут удалены сохранённые файлы твоих расшифровок.
                История задач в базе останется, но скачать старые файлы будет нельзя.
            """.trimIndent(),
            replyMarkup = clearStorageConfirmationKeyboard()
        )
    }

    private fun handleClearStorageConfirm(context: CallbackContext) {
        val userId = context.userId ?: return
        val result = storageCleanupService.cleanupUserStorage(userId)

        render(
            context = context,
            text = """
                Хранилище очищено.

                Удалено файлов: ${result.deletedFilesCount}
                Очищено задач: ${result.cleanedTasksCount}
            """.trimIndent(),
            replyMarkup = menuOnlyKeyboard()
        )
    }

    private fun showTaskDetail(
        context: CallbackContext,
        data: String,
        fallbackToHistory: Boolean = false
    ) {
        val taskId = parseTaskId(data.substringAfter(":", ""))
        val userId = context.userId

        if (taskId == null || userId == null) {
            render(context, "Задача не найдена.", menuOnlyKeyboard())
            return
        }

        val task = taskService.getUserTask(taskId, userId)

        if (task == null) {
            if (fallbackToHistory) {
                showHistory(context.chatId, userId, context.messageId)
            } else {
                render(context, "Задача не найдена.", menuOnlyKeyboard())
            }
            return
        }

        render(
            context = context,
            text = buildTaskDetailMessage(task),
            replyMarkup = taskDetailKeyboard(task),
            disableWebPagePreview = true
        )
    }

    private fun handleDeleteFilesRequest(
        context: CallbackContext,
        data: String
    ) {
        val task = getCallbackTask(context, data) ?: return

        render(
            context = context,
            text = """
                Удалить файлы этой задачи?

                Задача останется в истории, но скачать старые результаты будет нельзя.
            """.trimIndent(),
            replyMarkup = deleteFilesConfirmationKeyboard(task.id)
        )
    }

    private fun handleDeleteFilesConfirm(
        context: CallbackContext,
        data: String
    ) {
        val taskId = parseTaskId(data.substringAfter(":", ""))
        val userId = context.userId

        if (taskId == null || userId == null) {
            render(context, "Задача не найдена.", menuOnlyKeyboard())
            return
        }

        val result = storageCleanupService.cleanupTaskFiles(taskId, userId)

        if (result == null) {
            render(context, "Задача не найдена.", menuOnlyKeyboard())
            return
        }

        val text = if (result.deletedFilesCount == 0) {
            "У этой задачи нет сохранённых файлов."
        } else {
            """
                Файлы задачи удалены из хранилища.

                Удалено файлов: ${result.deletedFilesCount}

                Сообщения с уже отправленными файлами в чате не удалялись.
            """.trimIndent()
        }

        render(
            context = context,
            text = text,
            replyMarkup = afterTaskActionKeyboard(taskId)
        )
    }

    private fun handleDeleteHistoryRequest(
        context: CallbackContext,
        data: String
    ) {
        val task = getCallbackTask(context, data) ?: return

        render(
            context = context,
            text = """
                Удалить задачу из истории?

                Она исчезнет из раздела «Мои задачи».
                Файлы результата тоже будут удалены, если они есть.
            """.trimIndent(),
            replyMarkup = deleteHistoryConfirmationKeyboard(task.id)
        )
    }

    private fun handleDeleteHistoryConfirm(
        context: CallbackContext,
        data: String
    ) {
        val taskId = parseTaskId(data.substringAfter(":", ""))
        val userId = context.userId

        if (taskId == null || userId == null) {
            render(context, "Задача не найдена.", menuOnlyKeyboard())
            return
        }

        val task = taskService.getUserTask(taskId, userId)

        if (task == null) {
            render(context, "Задача не найдена.", menuOnlyKeyboard())
            return
        }

        val fileResult = storageCleanupService.cleanupTaskFiles(taskId, userId)

        if (fileResult == null) {
            render(context, "Задача не найдена.", menuOnlyKeyboard())
            return
        }

        val artifactResult = artifactService.deleteTelegramArtifacts(taskId)
        taskService.softDeleteTask(taskId, userId)

        val failureText = if (artifactResult.failedDeletionsCount > 0) {
            "\n\nНекоторые сообщения не удалось удалить из чата."
        } else {
            ""
        }

        render(
            context = context,
            text = """
                Задача удалена из истории.

                Удалено файлов: ${fileResult.deletedFilesCount}
                Удалено сообщений с файлами: ${artifactResult.deletedMessagesCount}$failureText
            """.trimIndent(),
            replyMarkup = historyAndMenuKeyboard()
        )
    }

    private fun handleRepeatTask(
        context: CallbackContext,
        data: String
    ) {
        val taskId = parseTaskId(data.substringAfter(":", ""))
        val userId = context.userId

        if (taskId == null || userId == null) {
            render(context, "Задача не найдена.", menuOnlyKeyboard())
            return
        }

        val newTaskId = try {
            taskService.repeatTask(
                taskId = taskId,
                telegramUserId = userId,
                telegramChatId = context.chatId
            )
        } catch (ex: ActiveTaskLimitExceededException) {
            render(
                context = context,
                text = "У тебя уже есть активная задача. Дождись результата или проверь статус командой /status.",
                replyMarkup = menuOnlyKeyboard()
            )
            return
        }

        if (newTaskId == null) {
            render(context, "Задача не найдена.", menuOnlyKeyboard())
            return
        }

        val sent = render(
            context = context,
            text = formatSelectionText(),
            replyMarkup = formatSelectionKeyboard(newTaskId),
            disableWebPagePreview = true
        )

        if (sent != null) {
            taskService.updateProgressMessageId(newTaskId, sent.messageId)
        }
    }

    private fun getCallbackTask(
        context: CallbackContext,
        data: String
    ): TranscriptionTask? {
        val taskId = parseTaskId(data.substringAfter(":", ""))
        val userId = context.userId

        if (taskId == null || userId == null) {
            render(context, "Задача не найдена.", menuOnlyKeyboard())
            return null
        }

        val task = taskService.getUserTask(taskId, userId)

        if (task == null) {
            render(context, "Задача не найдена.", menuOnlyKeyboard())
            return null
        }

        return task
    }

    private fun handleUnknownMessage(chatId: Long) {
        messageSender.sendText(
            chatId = chatId,
            text = "Пришли ссылку на видео или используй /help."
        )
    }

    private fun render(
        context: CallbackContext,
        text: String,
        replyMarkup: Map<String, Any>? = null,
        disableWebPagePreview: Boolean = false
    ): TelegramSentMessage? {
        return if (context.messageId != null) {
            messageSender.editMessageText(
                chatId = context.chatId,
                messageId = context.messageId,
                text = text,
                replyMarkup = replyMarkup,
                disableWebPagePreview = disableWebPagePreview
            )
        } else {
            messageSender.sendText(
                chatId = context.chatId,
                text = text,
                replyMarkup = replyMarkup,
                disableWebPagePreview = disableWebPagePreview
            )
        }
    }

    private fun mainMenuText(): String {
        return """
            Привет. Я Link2Action.

            Пришли ссылку на видео, а я сделаю расшифровку и отправлю результат в выбранном формате.

            Что можно сделать:
            — отправить ссылку на видео;
            — посмотреть историю задач;
            — скачать старый результат;
            — очистить свои файлы.
        """.trimIndent()
    }

    private fun formatSelectionText(): String {
        return """
            Ссылка получена.

            Выбери формат результата:
        """.trimIndent()
    }

    private fun buildTaskStatusMessage(task: TranscriptionTask): String {
        val details = mutableListOf(
            "Статус задачи: ${task.status}",
            "",
            "ID: ${task.id}",
            "Ссылка: ${task.sourceUrl}",
            "Создана: ${task.createdAt}",
            "Обновлена: ${task.updatedAt}"
        )

        if (!task.title.isNullOrBlank()) {
            details.add("Название: ${task.title}")
        }

        if (task.durationSeconds != null) {
            details.add("Длительность: ${formatDuration(task.durationSeconds!!)}")
        }

        if (!task.errorMessage.isNullOrBlank()) {
            details.add("Ошибка: ${task.errorMessage}")
        }

        details.add("Файлы: ${fileState(task)}")

        return details.joinToString("\n")
    }

    private fun buildTaskDetailMessage(task: TranscriptionTask): String {
        val lines = mutableListOf(
            "Задача ${shortTaskId(task.id)}",
            "",
            "Статус: ${statusText(task.status)}"
        )

        task.title
            ?.takeIf { it.isNotBlank() }
            ?.let { lines.add("Видео: $it") }
            ?: lines.add("Ссылка: ${task.sourceUrl}")

        task.durationSeconds
            ?.let { lines.add("Длительность: ${formatDuration(it)}") }

        task.language
            ?.takeIf { it.isNotBlank() }
            ?.let { lines.add("Язык: $it") }

        lines.add("Форматы: ${formatDisplay(task.requestedFormat)}")
        lines.add("Файлы: ${detailFileState(task)}")

        if (task.status == TranscriptionStatus.FAILED && !task.errorMessage.isNullOrBlank()) {
            lines.add("")
            lines.add("Ошибка: ${task.errorMessage}")
        }

        lines.add("")
        lines.add("Создана: ${timeFormatter.format(task.createdAt)}")

        return lines.joinToString("\n")
    }

    private fun buildActiveTasksMessage(tasks: List<TranscriptionTask>): String {
        val lines = mutableListOf("Сейчас обрабатывается:", "")

        tasks.forEachIndexed { index, task ->
            lines.add("${index + 1}. ${taskDisplayTitle(task)}")
            lines.add("Статус: ${task.status}")
            lines.add("Создано: ${timeFormatter.format(task.createdAt)}")

            if (index != tasks.lastIndex) {
                lines.add("")
            }
        }

        return lines.joinToString("\n")
    }

    private fun buildHistoryMessage(tasks: List<TranscriptionTask>): String {
        val lines = mutableListOf("📜 Мои задачи", "")

        tasks.forEachIndexed { index, task ->
            lines.add("${numberEmoji(index + 1)} ${statusLabel(task.status)}")
            task.title
                ?.takeIf { it.isNotBlank() }
                ?.let { lines.add("Название: $it") }
                ?: lines.add("Ссылка: ${task.sourceUrl}")
            lines.add("ID: ${shortTaskId(task.id).removeSuffix("...")}")
            task.durationSeconds
                ?.let { lines.add("Длительность: ${formatDuration(it)}") }
            lines.add("Файлы: ${fileState(task)}")

            if (index != tasks.lastIndex) {
                lines.add("")
            }
        }

        return lines.joinToString("\n")
    }

    private fun historyKeyboard(tasks: List<TranscriptionTask>): Map<String, Any> {
        val rows = tasks.mapIndexed { index, task ->
            listOf(button("${numberEmoji(index + 1)} Открыть", "$CALLBACK_TASK_OPEN:${task.id}"))
        }.toMutableList()

        rows.add(listOf(button("⬅️ Назад", CALLBACK_MENU)))
        rows.add(listOf(button("🏠 Меню", CALLBACK_MENU)))

        return inlineKeyboard(*rows.toTypedArray())
    }

    private fun taskDetailKeyboard(task: TranscriptionTask): Map<String, Any> {
        val rows = mutableListOf<List<Map<String, String>>>()
        val fileAvailability = fileAvailability(task)
        val downloadButtons = mutableListOf<Map<String, String>>()

        if (task.status == TranscriptionStatus.WAITING_FORMAT) {
            rows.add(
                listOf(
                    button("📄 TXT", "$CALLBACK_FORMAT_SELECT:${task.id}:TXT"),
                    button("📝 Markdown", "$CALLBACK_FORMAT_SELECT:${task.id}:MD")
                )
            )
            rows.add(listOf(button("📄 + 📝 Оба", "$CALLBACK_FORMAT_SELECT:${task.id}:BOTH")))
            rows.add(listOf(button("Отмена", "$CALLBACK_FORMAT_CANCEL:${task.id}")))
        }

        if (fileAvailability.hasTxt) {
            downloadButtons.add(button("📄 TXT", "$CALLBACK_GET_TXT:${task.id}"))
        }

        if (fileAvailability.hasMd) {
            downloadButtons.add(button("📝 MD", "$CALLBACK_GET_MD:${task.id}"))
        }

        if (downloadButtons.isNotEmpty()) {
            rows.add(downloadButtons)
            rows.add(listOf(button("📥 Скачать результат", "$CALLBACK_GET_RESULT:${task.id}")))
        }

        if (hasStoredFilePaths(task)) {
            rows.add(listOf(button("🧹 Удалить файлы", "$CALLBACK_DELETE_FILES_REQUEST:${task.id}")))
        }

        rows.add(listOf(button("🔁 Повторить", "$CALLBACK_REPEAT_TASK:${task.id}")))
        rows.add(listOf(button("❌ Удалить из истории", "$CALLBACK_DELETE_HISTORY_REQUEST:${task.id}")))
        rows.add(
            listOf(
                button("⬅️ Назад к задачам", CALLBACK_HISTORY),
                button("🏠 Меню", CALLBACK_MENU)
            )
        )

        return inlineKeyboard(*rows.toTypedArray())
    }

    private fun startKeyboard(): Map<String, Any> {
        return inlineKeyboard(
            listOf(button("📜 Мои задачи", CALLBACK_HISTORY)),
            listOf(button("🔄 Статус", CALLBACK_STATUS_ACTIVE)),
            listOf(button("🧹 Очистить мои файлы", CALLBACK_CLEAR_STORAGE_REQUEST)),
            listOf(button("ℹ️ Помощь", CALLBACK_HELP))
        )
    }

    private fun clearStorageConfirmationKeyboard(): Map<String, Any> {
        return inlineKeyboard(
            listOf(
                button("Да, очистить", CALLBACK_CLEAR_STORAGE_CONFIRM),
                button("Отмена", CALLBACK_CLEAR_STORAGE_CANCEL)
            ),
            listOf(button("🏠 Меню", CALLBACK_MENU))
        )
    }

    private fun formatSelectionKeyboard(taskId: UUID): Map<String, Any> {
        return inlineKeyboard(
            listOf(
                button("📄 TXT", "$CALLBACK_FORMAT_SELECT:$taskId:TXT"),
                button("📝 Markdown", "$CALLBACK_FORMAT_SELECT:$taskId:MD")
            ),
            listOf(button("📄 + 📝 Оба", "$CALLBACK_FORMAT_SELECT:$taskId:BOTH")),
            listOf(
                button("Отмена", "$CALLBACK_FORMAT_CANCEL:$taskId"),
                button("🏠 Меню", CALLBACK_MENU)
            )
        )
    }

    private fun acceptedTaskKeyboard(): Map<String, Any> {
        return inlineKeyboard(
            listOf(button("🔄 Статус", CALLBACK_STATUS_ACTIVE)),
            listOf(button("📜 Мои задачи", CALLBACK_HISTORY)),
            listOf(button("🏠 Меню", CALLBACK_MENU))
        )
    }

    private fun activeStatusKeyboard(): Map<String, Any> {
        return inlineKeyboard(
            listOf(button("📜 Мои задачи", CALLBACK_HISTORY)),
            listOf(button("🏠 Меню", CALLBACK_MENU))
        )
    }

    private fun deleteFilesConfirmationKeyboard(taskId: UUID): Map<String, Any> {
        return inlineKeyboard(
            listOf(
                button("Да, удалить файлы", "$CALLBACK_DELETE_FILES_CONFIRM:$taskId"),
                button("Отмена", "$CALLBACK_DELETE_FILES_CANCEL:$taskId")
            ),
            listOf(button("🏠 Меню", CALLBACK_MENU))
        )
    }

    private fun deleteHistoryConfirmationKeyboard(taskId: UUID): Map<String, Any> {
        return inlineKeyboard(
            listOf(button("Да, удалить из истории", "$CALLBACK_DELETE_HISTORY_CONFIRM:$taskId")),
            listOf(
                button("Отмена", "$CALLBACK_DELETE_HISTORY_CANCEL:$taskId"),
                button("🏠 Меню", CALLBACK_MENU)
            )
        )
    }

    private fun afterTaskActionKeyboard(taskId: UUID): Map<String, Any> {
        return inlineKeyboard(
            listOf(button("⬅️ К задаче", "$CALLBACK_TASK_DETAIL:$taskId")),
            listOf(
                button("📜 Мои задачи", CALLBACK_HISTORY),
                button("🏠 Меню", CALLBACK_MENU)
            )
        )
    }

    private fun historyAndMenuKeyboard(): Map<String, Any> {
        return inlineKeyboard(
            listOf(button("📜 Мои задачи", CALLBACK_HISTORY)),
            listOf(button("🏠 Меню", CALLBACK_MENU))
        )
    }

    private fun menuOnlyKeyboard(): Map<String, Any> {
        return inlineKeyboard(listOf(button("🏠 Меню", CALLBACK_MENU)))
    }

    private fun resultFiles(
        task: TranscriptionTask,
        fileType: ResultFileType
    ): List<ResultFile> {
        val files = mutableListOf<ResultFile>()

        val mdPath = task.resultMdPath
        if ((fileType == ResultFileType.ALL || fileType == ResultFileType.MD) && !mdPath.isNullOrBlank()) {
            files.add(
                ResultFile(
                    path = Path.of(mdPath),
                    caption = buildTranscriptCaption(task, "Markdown", "📝"),
                    artifactType = ArtifactType.MD
                )
            )
        }

        val txtPath = task.resultTxtPath
        if ((fileType == ResultFileType.ALL || fileType == ResultFileType.TXT) && !txtPath.isNullOrBlank()) {
            files.add(
                ResultFile(
                    path = Path.of(txtPath),
                    caption = buildTranscriptCaption(task, "TXT", "📄"),
                    artifactType = ArtifactType.TXT
                )
            )
        }

        return files
    }

    private fun taskDisplayTitle(task: TranscriptionTask): String {
        return when {
            !task.title.isNullOrBlank() -> task.title!!
            task.status == TranscriptionStatus.FAILED && !task.errorMessage.isNullOrBlank() -> task.errorMessage!!
            else -> task.sourceUrl
        }
    }

    private fun fileState(task: TranscriptionTask): String {
        return when (task.status) {
            TranscriptionStatus.WAITING_FORMAT -> "Ожидает выбора формата"
            TranscriptionStatus.QUEUED,
            TranscriptionStatus.PROCESSING -> "ещё не готовы"
            TranscriptionStatus.FAILED -> "не созданы"
            TranscriptionStatus.COMPLETED -> {
                val availability = fileAvailability(task)
                if (availability.hasAny) {
                    availability.shortLabel
                } else {
                    "удалены"
                }
            }
        }
    }

    private fun fileAvailability(task: TranscriptionTask): FileAvailability {
        val hasTxt = pathExists(task.resultTxtPath)
        val hasMd = pathExists(task.resultMdPath)
        val shortLabel = when {
            hasTxt && hasMd -> "TXT, MD"
            hasTxt -> "TXT"
            hasMd -> "MD"
            else -> "нет"
        }

        return FileAvailability(
            hasTxt = hasTxt,
            hasMd = hasMd,
            shortLabel = shortLabel
        )
    }

    private fun hasStoredFilePaths(task: TranscriptionTask): Boolean {
        return !task.resultTxtPath.isNullOrBlank() || !task.resultMdPath.isNullOrBlank()
    }

    private fun pathExists(path: String?): Boolean {
        if (path.isNullOrBlank()) {
            return false
        }

        return try {
            Files.exists(Path.of(path))
        } catch (ex: Exception) {
            false
        }
    }

    private fun statusLabel(status: TranscriptionStatus): String {
        return when (status) {
            TranscriptionStatus.WAITING_FORMAT -> "🕓 Ожидает выбора формата"
            TranscriptionStatus.COMPLETED -> "✅ Готово"
            TranscriptionStatus.PROCESSING -> "🔄 В работе"
            TranscriptionStatus.QUEUED -> "⏳ В очереди"
            TranscriptionStatus.FAILED -> "❌ Ошибка"
        }
    }

    private fun statusText(status: TranscriptionStatus): String {
        return when (status) {
            TranscriptionStatus.COMPLETED -> "✅ Готово"
            TranscriptionStatus.PROCESSING -> "🔄 В работе"
            TranscriptionStatus.QUEUED -> "⏳ В очереди"
            TranscriptionStatus.FAILED -> "❌ Ошибка"
            TranscriptionStatus.WAITING_FORMAT -> "Ожидает выбора формата"
        }
    }

    private fun detailFileState(task: TranscriptionTask): String {
        return when (task.status) {
            TranscriptionStatus.WAITING_FORMAT -> "ожидает выбора формата"
            TranscriptionStatus.QUEUED,
            TranscriptionStatus.PROCESSING -> "ещё не готовы"
            TranscriptionStatus.FAILED -> "не созданы"
            TranscriptionStatus.COMPLETED -> {
                val availability = fileAvailability(task)
                if (availability.hasAny) {
                    "доступны"
                } else {
                    "удалены из хранилища"
                }
            }
        }
    }

    private fun formatDisplay(requestedFormat: String): String {
        return requestedFormat
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "PENDING" }
            .joinToString(", ")
            .ifBlank { "не выбран" }
    }

    private fun buildTranscriptCaption(
        task: TranscriptionTask,
        format: String,
        icon: String
    ): String {
        val lines = mutableListOf(
            "$icon $format расшифровка",
            "",
            "Задача: ${task.id.toString().take(8)}"
        )

        task.title
            ?.takeIf { it.isNotBlank() }
            ?.let { lines.add("Видео: ${it.take(CAPTION_TITLE_LIMIT)}") }

        task.durationSeconds
            ?.let { lines.add("Длительность: ${formatDuration(it)}") }

        task.language
            ?.takeIf { it.isNotBlank() }
            ?.let { lines.add("Язык: $it") }

        return lines.joinToString("\n")
    }

    private fun numberEmoji(index: Int): String {
        return when (index) {
            1 -> "1️⃣"
            2 -> "2️⃣"
            3 -> "3️⃣"
            4 -> "4️⃣"
            5 -> "5️⃣"
            6 -> "6️⃣"
            7 -> "7️⃣"
            8 -> "8️⃣"
            9 -> "9️⃣"
            10 -> "🔟"
            else -> index.toString()
        }
    }

    private fun shortTaskId(taskId: UUID): String {
        return "${taskId.toString().take(8)}..."
    }

    private fun parseTaskId(rawTaskId: String): UUID? {
        return try {
            UUID.fromString(rawTaskId.trim())
        } catch (ex: IllegalArgumentException) {
            null
        }
    }

    private fun commandArgument(text: String): String {
        return text.split(Regex("\\s+"), limit = 2)
            .getOrNull(1)
            ?.trim()
            .orEmpty()
    }

    private fun isCommand(
        text: String,
        command: String
    ): Boolean {
        val commandPart = text.split(Regex("\\s+"), limit = 2)
            .firstOrNull()
            ?.substringBefore("@")

        return commandPart == command
    }

    private fun formatsFromSelection(selection: String): List<String> {
        return when (selection.uppercase()) {
            "TXT" -> listOf("TXT")
            "MD" -> listOf("MD")
            "BOTH" -> listOf("TXT", "MD")
            else -> listOf("TXT", "MD")
        }
    }

    private fun formatLabel(formats: Collection<String>): String {
        return when (formats.map { it.uppercase() }.toSet()) {
            setOf("TXT") -> "TXT"
            setOf("MD") -> "Markdown"
            else -> "TXT и Markdown"
        }
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return if (hours > 0) {
            "%02d:%02d:%02d".format(hours, minutes, secs)
        } else {
            "%02d:%02d".format(minutes, secs)
        }
    }

    private fun inlineKeyboard(vararg rows: List<Map<String, String>>): Map<String, Any> {
        return mapOf("inline_keyboard" to rows.toList())
    }

    private fun button(
        text: String,
        callbackData: String
    ): Map<String, String> {
        return mapOf(
            "text" to text,
            "callback_data" to callbackData
        )
    }

    private data class CallbackContext(
        val chatId: Long,
        val messageId: Long?,
        val userId: Long?
    )

    private data class ResultFile(
        val path: Path,
        val caption: String,
        val artifactType: ArtifactType
    )

    private data class FileAvailability(
        val hasTxt: Boolean,
        val hasMd: Boolean,
        val shortLabel: String
    ) {
        val hasAny: Boolean = hasTxt || hasMd
    }

    private enum class ResultFileType {
        ALL,
        TXT,
        MD
    }

    private companion object {
        const val HISTORY_LIMIT = 5
        const val CALLBACK_MENU = "menu"
        const val CALLBACK_HISTORY = "history"
        const val CALLBACK_STATUS_ACTIVE = "status_active"
        const val CALLBACK_CLEAR_STORAGE_REQUEST = "clear_storage_request"
        const val CALLBACK_CLEAR_STORAGE_CONFIRM = "clear_storage_confirm"
        const val CALLBACK_CLEAR_STORAGE_CANCEL = "clear_storage_cancel"
        const val CALLBACK_CLEAN_STORAGE_REQUEST = "clean_storage_request"
        const val CALLBACK_CLEAN_STORAGE_CONFIRM = "clean_storage_confirm"
        const val CALLBACK_CLEAN_STORAGE_CANCEL = "clean_storage_cancel"
        const val CALLBACK_HELP = "help"
        const val CALLBACK_TASK_DETAIL = "task_detail"
        const val CALLBACK_TASK_OPEN = "task_open"
        const val CALLBACK_GET_RESULT = "get_result"
        const val CALLBACK_GET_TXT = "get_txt"
        const val CALLBACK_GET_MD = "get_md"
        const val CALLBACK_DELETE_FILES_REQUEST = "delete_files_request"
        const val CALLBACK_DELETE_FILES_CONFIRM = "delete_files_confirm"
        const val CALLBACK_DELETE_FILES_CANCEL = "delete_files_cancel"
        const val CALLBACK_DELETE_HISTORY_REQUEST = "delete_history_request"
        const val CALLBACK_DELETE_HISTORY_CONFIRM = "delete_history_confirm"
        const val CALLBACK_DELETE_HISTORY_CANCEL = "delete_history_cancel"
        const val CALLBACK_OLD_DELETE_TASK_REQUEST = "delete_task_request"
        const val CALLBACK_OLD_DELETE_TASK_CONFIRM = "delete_task_confirm"
        const val CALLBACK_OLD_DELETE_TASK_CANCEL = "delete_task_cancel"
        const val CALLBACK_REPEAT_TASK = "repeat_task"
        const val CALLBACK_FORMAT_SELECT = "format_select"
        const val CALLBACK_FORMAT_CANCEL = "format_cancel"
        const val CAPTION_TITLE_LIMIT = 120
    }
}
