package com.link2action.bot.observability

import com.link2action.bot.task.TranscriptionStatus
import com.link2action.bot.task.TranscriptionTaskRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import java.time.Duration
import kotlin.test.assertEquals

class TranscriptionMetricsTest {

    @Test
    fun `records transcription business metrics`() {
        val repository = Mockito.mock(TranscriptionTaskRepository::class.java)
        Mockito.`when`(
            repository.countByDeletedAtIsNullAndStatusIn(
                ArgumentMatchers.anyCollection<TranscriptionStatus>()
            )
        ).thenReturn(2)

        val registry = SimpleMeterRegistry()
        val metrics = TranscriptionMetrics(registry, repository)

        metrics.recordTaskCreated()
        metrics.recordTaskCompleted(Duration.ofSeconds(3))
        metrics.recordTaskFailed(Duration.ofSeconds(5))
        metrics.recordResultEvent("COMPLETED")

        assertEquals(1.0, registry.get("link2action.transcription.tasks.created").counter().count())
        assertEquals(1.0, registry.get("link2action.transcription.tasks.completed").counter().count())
        assertEquals(1.0, registry.get("link2action.transcription.tasks.failed").counter().count())
        assertEquals(2.0, registry.get("link2action.transcription.tasks.active").gauge().value())
        assertEquals(2, registry.get("link2action.transcription.task.duration").timer().count())
        assertEquals(
            1.0,
            registry.get("link2action.rabbitmq.result.events")
                .tag("status", "COMPLETED")
                .counter()
                .count()
        )
    }
}
