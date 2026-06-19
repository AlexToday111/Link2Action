package com.link2action.bot.integration

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.RabbitMQContainer

@SpringBootTest(
    properties = [
        "app.telegram.bot-token=test-token",
        "app.telegram.bot-username=Link2ActionBot",
        "app.telegram.polling-delay-ms=600000",
        "app.transcription.max-active-tasks-per-user=10",
        "spring.task.scheduling.enabled=false"
    ]
)
abstract class IntegrationTestBase {

    companion object {
        @JvmStatic
        val postgres = TestPostgresContainer().apply {
            start()
        }

        @JvmStatic
        val rabbitmq = RabbitMQContainer("rabbitmq:3-management-alpine").apply {
            start()
        }

        @DynamicPropertySource
        @JvmStatic
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.rabbitmq.host") { rabbitmq.host }
            registry.add("spring.rabbitmq.port") { rabbitmq.amqpPort }
            registry.add("spring.rabbitmq.username") { rabbitmq.adminUsername }
            registry.add("spring.rabbitmq.password") { rabbitmq.adminPassword }
        }
    }
}

class TestPostgresContainer : PostgreSQLContainer<TestPostgresContainer>("postgres:16-alpine")
