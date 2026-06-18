package com.link2action.bot.telegram

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.util.concurrent.atomic.AtomicLong

@Component
class TelegramUpdateHandler(
    @Qualifier("telegramRestClient")
    private val telegramRestClient: RestClient,
    private val commandRouter: TelegramCommandRouter
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastProcessedUpdateId = AtomicLong(0)

    @Scheduled(fixedDelayString = "\${app.telegram.polling-delay-ms:1000}")
    fun pollUpdates() {
        try {
            val offset = lastProcessedUpdateId.get() + 1

            val response = telegramRestClient
                .get()
                .uri { uriBuilder ->
                    uriBuilder
                        .path("/getUpdates")
                        .queryParam("offset", offset)
                        .queryParam("timeout", 25)
                        .build()
                }
                .retrieve()
                .body(GetUpdatesResponse::class.java)

            if (response == null || !response.ok) {
                log.warn("Telegram getUpdates returned non-ok response: {}", response)
                return
            }

            response.result.forEach { update ->
                handleUpdate(update)
                lastProcessedUpdateId.updateAndGet { current ->
                    maxOf(current, update.updateId)
                }
            }
        } catch (ex: Exception) {
            log.error("Failed to poll Telegram updates", ex)
        }
    }

    private fun handleUpdate(update: TelegramUpdate) {
        val message = update.message

        if (message == null) {
            log.debug("Skipping update without message: updateId={}", update.updateId)
            return
        }

        commandRouter.route(message)
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class GetUpdatesResponse(
    val ok: Boolean = false,
    val result: List<TelegramUpdate> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramUpdate(
    @JsonProperty("update_id")
    val updateId: Long,

    val message: TelegramMessage? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramMessage(
    @JsonProperty("message_id")
    val messageId: Long,

    val chat: TelegramChat,

    val from: TelegramUser? = null,

    val text: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramChat(
    val id: Long,
    val type: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TelegramUser(
    val id: Long,

    @JsonProperty("is_bot")
    val isBot: Boolean = false,

    @JsonProperty("first_name")
    val firstName: String? = null,

    val username: String? = null
)
