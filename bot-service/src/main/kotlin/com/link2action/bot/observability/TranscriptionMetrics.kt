package com.link2action.bot.observability

import com.link2action.bot.task.TranscriptionStatus
import com.link2action.bot.task.TranscriptionTaskRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class TranscriptionMetrics(
    private val meterRegistry: MeterRegistry,
    private val taskRepository: TranscriptionTaskRepository
) {
    private val activeStatuses = setOf(
        TranscriptionStatus.QUEUED,
        TranscriptionStatus.PROCESSING
    )

    private val tasksCreated = Counter
        .builder("link2action.transcription.tasks.created")
        .description("Total transcription tasks created")
        .register(meterRegistry)

    private val tasksCompleted = Counter
        .builder("link2action.transcription.tasks.completed")
        .description("Total transcription tasks completed")
        .register(meterRegistry)

    private val tasksFailed = Counter
        .builder("link2action.transcription.tasks.failed")
        .description("Total transcription tasks failed")
        .register(meterRegistry)

    private val taskDuration = Timer
        .builder("link2action.transcription.task.duration")
        .description("Transcription task processing duration")
        .publishPercentileHistogram()
        .register(meterRegistry)

    init {
        Gauge
            .builder("link2action.transcription.tasks.active", taskRepository) { repository ->
                repository.countByDeletedAtIsNullAndStatusIn(activeStatuses).toDouble()
            }
            .description("Current active transcription tasks")
            .register(meterRegistry)
    }

    fun recordTaskCreated() {
        tasksCreated.increment()
    }

    fun recordTaskCompleted(duration: Duration?) {
        tasksCompleted.increment()
        recordDuration(duration)
    }

    fun recordTaskFailed(duration: Duration?) {
        tasksFailed.increment()
        recordDuration(duration)
    }

    fun recordResultEvent(status: String) {
        Counter
            .builder("link2action.rabbitmq.result.events")
            .description("Total RabbitMQ result events consumed")
            .tag("status", status)
            .register(meterRegistry)
            .increment()
    }

    private fun recordDuration(duration: Duration?) {
        if (duration == null || duration.isNegative || duration.isZero) {
            return
        }

        taskDuration.record(duration)
    }
}
