package com.link2action.bot.common

import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ClockProvider {

    fun now(): Instant {
        return Instant.now()
    }
}