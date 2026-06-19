package com.link2action.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
class AppProperties (
    val telegram: Telegram,
    val rabbit: Rabbit,
    val storage: Storage,
    val transcription: Transcription
){
    data class Telegram (
        val botToken: String,
        val botUsername: String,
        val pollingDelayMs: Long = 1000
    )

    data class Rabbit(
        val exchange: String,
        val requestQueue: String,
        val requestRetryQueue: String,
        val requestDlq: String,
        val resultQueue: String,
        val requestRoutingKey: String,
        val retryRoutingKey: String,
        val dlqRoutingKey: String,
        val completedRoutingKey: String,
        val failedRoutingKey: String,
        val progressRoutingKey: String,
        val retryDelayMs: Int
    )

    data class Storage(
        val resultsBasePath: String
    )

    data class Transcription (
        val maxActiveTasksPerUser: Int = 1
    )
}
