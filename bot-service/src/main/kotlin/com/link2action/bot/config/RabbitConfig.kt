package com.link2action.bot.config

import com.fasterxml.jackson.databind.ObjectMapper
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
            .withArgument("x-dead-letter-exchange", appProperties.rabbit.exchange)
            .withArgument("x-dead-letter-routing-key", appProperties.rabbit.dlqRoutingKey)
            .build()
    }

    @Bean
    fun transcriptionRequestsRetryQueue(): Queue {
        return QueueBuilder
            .durable(appProperties.rabbit.requestRetryQueue)
            .withArgument("x-message-ttl", appProperties.rabbit.retryDelayMs)
            .withArgument("x-dead-letter-exchange", appProperties.rabbit.exchange)
            .withArgument("x-dead-letter-routing-key", appProperties.rabbit.requestRoutingKey)
            .build()
    }

    @Bean
    fun transcriptionRequestsDlq(): Queue {
        return QueueBuilder
            .durable(appProperties.rabbit.requestDlq)
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
    fun transcriptionRetryBinding(
        transcriptionRequestsRetryQueue: Queue,
        linkscribeExchange: DirectExchange
    ): Binding {
        return BindingBuilder
            .bind(transcriptionRequestsRetryQueue)
            .to(linkscribeExchange)
            .with(appProperties.rabbit.retryRoutingKey)
    }

    @Bean
    fun transcriptionDlqBinding(
        transcriptionRequestsDlq: Queue,
        linkscribeExchange: DirectExchange
    ): Binding {
        return BindingBuilder
            .bind(transcriptionRequestsDlq)
            .to(linkscribeExchange)
            .with(appProperties.rabbit.dlqRoutingKey)
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
    fun transcriptionProgressBinding(
        transcriptionResultsQueue: Queue,
        linkscribeExchange: DirectExchange
    ): Binding {
        return BindingBuilder
            .bind(transcriptionResultsQueue)
            .to(linkscribeExchange)
            .with(appProperties.rabbit.progressRoutingKey)
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
