package com.link2action.bot.queue

import com.link2action.bot.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Component

@Component
class TranscriptionRequestPublisher (
    private val rabbitTemplate: RabbitTemplate,
    private val appProperties: AppProperties
){
    private val log = LoggerFactory.getLogger(javaClass)

    fun publish(event: TranscriptionRequestedEvent) {
        rabbitTemplate.convertAndSend(
            appProperties.rabbit.exchange,
            appProperties.rabbit.requestRoutingKey,
            event
        )

        log.info(
            "Published transcription request event: taskId={}, routingKey={}",
            event.taskId,
            appProperties.rabbit.requestRoutingKey
        )
    }
}