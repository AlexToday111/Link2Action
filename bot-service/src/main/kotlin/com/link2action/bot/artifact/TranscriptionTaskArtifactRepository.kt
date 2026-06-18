package com.link2action.bot.artifact

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TranscriptionTaskArtifactRepository : JpaRepository<TranscriptionTaskArtifact, UUID> {

    fun findByTaskId(taskId: UUID): List<TranscriptionTaskArtifact>
}
