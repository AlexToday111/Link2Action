package com.linkscribe.bot.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.link2action.bot.config.AppProperties
import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter
import org.springframework.amqp.support.converter.MessageConverter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.amqp.rabbit.annotation.EnableRabbit

@Configuration
@EnableRabbit
class RabbitConfig(
    private val appProperties: AppProperties
) {

    @Bean
    fun linkscribeExchange(): DirectExchange {
        return DirectExchange(appProperties.rabbit.exchange, true, false)
    }

    @Bean
    fun transcriptionRequestsQueue(): Queue {
        return QueueBuilder
            .durable(appProperties.rabbit.requestQueue)
            .build()
    }

    @Bean
    fun transcriptionResultsQueue(): Queue {
        return QueueBuilder
            .durable(appProperties.rabbit.resultQueue)
            .build()
    }

    @Bean
    fun transcriptionRequestBinding(
        transcriptionRequestsQueue: Queue,
        linkscribeExchange: DirectExchange
    ): Binding {
        return BindingBuilder
            .bind(transcriptionRequestsQueue)
            .to(linkscribeExchange)
            .with(appProperties.rabbit.requestRoutingKey)
    }

    @Bean
    fun transcriptionCompletedBinding(
        transcriptionResultsQueue: Queue,
        linkscribeExchange: DirectExchange
    ): Binding {
        return BindingBuilder
            .bind(transcriptionResultsQueue)
            .to(linkscribeExchange)
            .with(appProperties.rabbit.completedRoutingKey)
    }

    @Bean
    fun transcriptionFailedBinding(
        transcriptionResultsQueue: Queue,
        linkscribeExchange: DirectExchange
    ): Binding {
        return BindingBuilder
            .bind(transcriptionResultsQueue)
            .to(linkscribeExchange)
            .with(appProperties.rabbit.failedRoutingKey)
    }

    @Bean
    fun rabbitMessageConverter(objectMapper: ObjectMapper): MessageConverter {
        return Jackson2JsonMessageConverter(objectMapper)
    }

    @Bean
    fun rabbitTemplate(
        connectionFactory: ConnectionFactory,
        rabbitMessageConverter: MessageConverter
    ): RabbitTemplate {
        return RabbitTemplate(connectionFactory).apply {
            messageConverter = rabbitMessageConverter
        }
    }

    @Bean
    fun rabbitListenerContainerFactory(
        connectionFactory: ConnectionFactory,
        rabbitMessageConverter: MessageConverter
    ): SimpleRabbitListenerContainerFactory {
        return SimpleRabbitListenerContainerFactory().apply {
            setConnectionFactory(connectionFactory)
            setMessageConverter(rabbitMessageConverter)
            setDefaultRequeueRejected(false)
        }
    }
}