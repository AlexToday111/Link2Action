package com.Link2Action.bot.telegram

import com.link2action.bot.telegram.TelegramMessageSender
import com.link2action.bot.common.UrlExtractor
import com.link2action.bot.task.CreateTranscriptionTaskCommand
import com.link2action.bot.task.TranscriptionTaskService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class TelegramCommandRouter(
    private val taskService: TranscriptionTaskService,
    private val messageSender: TelegramMessageSender
) {
    private val log = LoggerFactory.getLogger(javaClass)

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
                text == "/start" -> handleStart(chatId)

                text == "/help" -> handleHelp(chatId)

                text.startsWith("/status") -> handleStatus(
                    chatId = chatId,
                    text = text
                )

                UrlExtractor.extract(text) != null -> handleVideoUrl(
                    chatId = chatId,
                    userId = userId,
                    text = text
                )

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

    private fun handleStart(chatId: Long) {
        messageSender.sendText(
            chatId = chatId,
            text = """
                Привет. Я Link2Action.

                Пришли мне ссылку на видео, а я поставлю задачу на расшифровку.
                Когда обработка завершится, я отправлю тебе результат в .txt и .md.

                Команды:
                /help — помощь
                /status <taskId> — проверить статус задачи
            """.trimIndent()
        )
    }

    private fun handleHelp(chatId: Long) {
        messageSender.sendText(
            chatId = chatId,
            text = """
                Как пользоваться:

                1. Скопируй ссылку на видео.
                2. Отправь её сюда обычным сообщением.
                3. Я создам задачу и передам её воркеру.
                4. После обработки пришлю .txt и .md файлы.

                Пример:
                https://youtu.be/...

                Проверить статус:
                /status <taskId>
            """.trimIndent()
        )
    }

    private fun handleStatus(chatId: Long, text: String) {
        val rawTaskId = text.removePrefix("/status").trim()

        if (rawTaskId.isBlank()) {
            messageSender.sendText(
                chatId = chatId,
                text = "Укажи ID задачи. Пример: /status 7f3a0f72-6e92-4e73-bdf7-efdf90c5aa31"
            )
            return
        }

        val taskId = try {
            UUID.fromString(rawTaskId)
        } catch (ex: IllegalArgumentException) {
            messageSender.sendText(
                chatId = chatId,
                text = "Некорректный taskId. Он должен быть в формате UUID."
            )
            return
        }

        val task = taskService.getTask(taskId)

        if (task == null) {
            messageSender.sendText(
                chatId = chatId,
                text = "Задача с таким ID не найдена."
            )
            return
        }

        messageSender.sendText(
            chatId = chatId,
            text = """
                Статус задачи: ${task.status}

                ID: ${task.id}
                Ссылка: ${task.sourceUrl}
                Создана: ${task.createdAt}
                Обновлена: ${task.updatedAt}
            """.trimIndent()
        )
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

        val taskId = taskService.createTask(
            CreateTranscriptionTaskCommand(
                telegramChatId = chatId,
                telegramUserId = userId,
                sourceUrl = url
            )
        )

        messageSender.sendText(
            chatId = chatId,
            text = """
                Задача принята.

                ID: $taskId
                Статус: QUEUED

                Я пришлю результат, когда воркер завершит расшифровку.
            """.trimIndent()
        )
    }

    private fun handleUnknownMessage(chatId: Long) {
        messageSender.sendText(
            chatId = chatId,
            text = "Пришли ссылку на видео или используй /help."
        )
    }
}