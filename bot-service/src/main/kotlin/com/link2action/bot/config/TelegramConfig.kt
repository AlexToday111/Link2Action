package com.link2action.bot.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
class TelegramConfig (
    private val appProperties: AppProperties
){
    @Bean
    fun telegramRestClient(restClientBuilder: RestClient.Builder): RestClient{
        val botToken = appProperties.telegram.botToken.trim()

        require(botToken.isNotBlank()) {
            "Telegram bot token is not configured. Please set TELEGRAM_BOT_TOKEN environment variable."
        }

        return restClientBuilder
            .baseUrl("https://api.telegram.org/bot$botToken")
            .build()
    }

    @Bean
    fun telegramFileRestClient(restClientBuilder: RestClient.Builder): RestClient {
        val botToken = appProperties.telegram.botToken.trim()

        require(botToken.isNotBlank()) {
            "Telegram bot token is not configured. Please set TELEGRAM_BOT_TOKEN environment variable."
        }

        return restClientBuilder
            .baseUrl("https://api.telegram.org/file/bot$botToken")
            .build()
    }
}