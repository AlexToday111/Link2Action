package com.link2action.bot.telegram

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.FileSystemResource
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.nio.file.Files
import java.nio.file.Path

@Component
class TelegramMessageSender(
    @Qualifier("telegramRestClient")
    private val telegramRestClient: RestClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun sendText(
        chatId: Long,
        text: String,
        replyMarkup: Map<String, Any>? = null,
        disableWebPagePreview: Boolean = false
    ): TelegramSentMessage? {
        return try {
            val request = mutableMapOf<String, Any>(
                "chat_id" to chatId,
                "text" to text,
                "disable_web_page_preview" to disableWebPagePreview
            )

            if (replyMarkup != null) {
                request["reply_markup"] = replyMarkup
            }

            val response = telegramRestClient
                .post()
                .uri("/sendMessage")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(TelegramMessageApiResponse::class.java)

            if (response == null || !response.ok) {
                log.warn("Telegram sendMessage returned non-ok response: {}", response)
                null
            } else {
                response.result
            }
        } catch (ex: Exception) {
            log.error("Failed to send Telegram message: chatId={}", chatId, ex)
            null
        }
    }

    fun editMessageText(
        chatId: Long,
        messageId: Long,
        text: String,
        replyMarkup: Map<String, Any>? = null,
        disableWebPagePreview: Boolean = false
    ): TelegramSentMessage? {
        return try {
            val request = mutableMapOf<String, Any>(
                "chat_id" to chatId,
                "message_id" to messageId,
                "text" to text,
                "disable_web_page_preview" to disableWebPagePreview
            )

            if (replyMarkup != null) {
                request["reply_markup"] = replyMarkup
            }

            val response = telegramRestClient
                .post()
                .uri("/editMessageText")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(TelegramMessageApiResponse::class.java)

            if (response == null || !response.ok) {
                log.warn("Telegram editMessageText returned non-ok response: {}", response)
                sendText(
                    chatId = chatId,
                    text = text,
                    replyMarkup = replyMarkup,
                    disableWebPagePreview = disableWebPagePreview
                )
            } else {
                response.result
            }
        } catch (ex: Exception) {
            log.warn("Failed to edit Telegram message, falling back to sendMessage: chatId={}, messageId={}", chatId, messageId, ex)
            sendText(
                chatId = chatId,
                text = text,
                replyMarkup = replyMarkup,
                disableWebPagePreview = disableWebPagePreview
            )
        }
    }

    fun editMessageReplyMarkup(
        chatId: Long,
        messageId: Long,
        replyMarkup: Map<String, Any>? = null
    ): TelegramSentMessage? {
        return try {
            val request = mutableMapOf<String, Any>(
                "chat_id" to chatId,
                "message_id" to messageId
            )

            if (replyMarkup != null) {
                request["reply_markup"] = replyMarkup
            }

            val response = telegramRestClient
                .post()
                .uri("/editMessageReplyMarkup")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(TelegramMessageApiResponse::class.java)

            if (response == null || !response.ok) {
                log.warn("Telegram editMessageReplyMarkup returned non-ok response: {}", response)
                null
            } else {
                response.result
            }
        } catch (ex: Exception) {
            log.warn("Failed to edit Telegram message reply markup: chatId={}, messageId={}", chatId, messageId, ex)
            null
        }
    }

    fun answerCallbackQuery(
        callbackQueryId: String,
        text: String? = null
    ) {
        try {
            val request = mutableMapOf<String, Any>(
                "callback_query_id" to callbackQueryId
            )

            if (!text.isNullOrBlank()) {
                request["text"] = text
            }

            val response = telegramRestClient
                .post()
                .uri("/answerCallbackQuery")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(TelegramSimpleApiResponse::class.java)

            if (response == null || !response.ok) {
                log.warn("Telegram answerCallbackQuery returned non-ok response: {}", response)
            }
        } catch (ex: Exception) {
            log.error("Failed to answer Telegram callback query: callbackQueryId={}", callbackQueryId, ex)
        }
    }

    fun sendDocument(
        chatId: Long,
        filePath: String,
        caption: String? = null
    ): TelegramSentMessage? {
        return sendDocument(
            chatId = chatId,
            path = Path.of(filePath),
            caption = caption
        )
    }

    fun sendDocument(
        chatId: Long,
        path: Path,
        caption: String? = null
    ): TelegramSentMessage? {
        return try {
            if (!Files.exists(path)) {
                log.error("Cannot send Telegram document. File does not exist: {}", path)
                sendText(
                    chatId = chatId,
                    text = "Файл результата не найден. Попробуй повторить задачу позже."
                )
                return null
            }

            if (!Files.isRegularFile(path)) {
                log.error("Cannot send Telegram document. Path is not a regular file: {}", path)
                sendText(
                    chatId = chatId,
                    text = "Результат найден, но это не файл. Попробуй повторить задачу позже."
                )
                return null
            }

            val body = LinkedMultiValueMap<String, Any>().apply {
                add("chat_id", chatId.toString())
                add("document", FileSystemResource(path))
                if (!caption.isNullOrBlank()) {
                    add("caption", caption)
                }
            }

            val response = telegramRestClient
                .post()
                .uri("/sendDocument")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(TelegramMessageApiResponse::class.java)

            if (response == null || !response.ok) {
                log.warn("Telegram sendDocument returned non-ok response: {}", response)
                null
            } else {
                response.result
            }
        } catch (ex: Exception) {
            log.error("Failed to send Telegram document: chatId={}, path={}", chatId, path, ex)

            sendText(
                chatId = chatId,
                text = "Не получилось отправить файл результата."
            )
            null
        }
    }

    fun deleteMessage(
        chatId: Long,
        messageId: Long
    ): Boolean {
        return try {
            val request = mapOf(
                "chat_id" to chatId,
                "message_id" to messageId
            )

            val response = telegramRestClient
                .post()
                .uri("/deleteMessage")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(TelegramSimpleApiResponse::class.java)

            if (response == null || !response.ok) {
                log.warn("Telegram deleteMessage returned non-ok response: {}", response)
                false
            } else {
                true
            }
        } catch (ex: Exception) {
            log.warn("Failed to delete Telegram message: chatId={}, messageId={}", chatId, messageId, ex)
            false
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramMessageApiResponse(
    val ok: Boolean = false,
    val result: TelegramSentMessage? = null,
    val description: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramSimpleApiResponse(
    val ok: Boolean = false,
    val description: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramSentMessage(
    @JsonProperty("message_id")
    val messageId: Long
)
