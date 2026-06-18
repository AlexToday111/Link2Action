package com.link2action.bot.config

import org.apache.coyote.Request
import org.hibernate.annotations.TimeZoneStorage
import org.springframework.amqp.rabbit.core.RabbitTemplate
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
        val botUserName: String
    )

    data class Rabbit(
        val exchange: String,
        val requestQueue: String,
        val resultQueue: String,
        val requestRoutingKey: String,
        val completedRoutingKey: String,
        val failedRoutingKey: String
    )

    data class Storage(
        val resultBasePath: String
    )

    data class Transcription (
        val maxActiveTasksPerUser: Int = 1
    )
}