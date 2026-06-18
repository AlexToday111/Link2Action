package com.link2action.bot.storage

import com.link2action.bot.common.ClockProvider
import com.link2action.bot.config.AppProperties
import com.link2action.bot.task.TranscriptionTask
import com.link2action.bot.task.TranscriptionTaskRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Service
class StorageCleanupService(
    private val repository: TranscriptionTaskRepository,
    private val appProperties: AppProperties,
    private val clockProvider: ClockProvider
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun cleanupUserStorage(telegramUserId: Long): StorageCleanupResult {
        val basePath = Path.of(appProperties.storage.resultsBasePath)
            .toAbsolutePath()
            .normalize()
        val tasks = repository.findByTelegramUserIdOrderByCreatedAtDesc(telegramUserId)
        return cleanupTasks(tasks, basePath)
    }

    @Transactional
    fun cleanupTaskStorage(
        taskId: UUID,
        telegramUserId: Long
    ): StorageCleanupResult? {
        return cleanupTaskFiles(taskId, telegramUserId)
    }

    @Transactional
    fun cleanupTaskFiles(
        taskId: UUID,
        telegramUserId: Long
    ): StorageCleanupResult? {
        val task = repository.findByIdAndTelegramUserIdAndDeletedAtIsNull(
            id = taskId,
            telegramUserId = telegramUserId
        ) ?: return null

        val basePath = Path.of(appProperties.storage.resultsBasePath)
            .toAbsolutePath()
            .normalize()

        return cleanupTasks(listOf(task), basePath)
    }

    @Transactional
    fun deleteTaskFromHistory(
        taskId: UUID,
        telegramUserId: Long
    ): StorageCleanupResult? {
        val task = repository.findByIdAndTelegramUserIdAndDeletedAtIsNull(
            id = taskId,
            telegramUserId = telegramUserId
        ) ?: return null

        val basePath = Path.of(appProperties.storage.resultsBasePath)
            .toAbsolutePath()
            .normalize()
        val result = cleanupTasks(listOf(task), basePath)

        task.markDeleted(clockProvider.now())

        return result
    }

    private fun cleanupTasks(
        tasks: List<TranscriptionTask>,
        basePath: Path
    ): StorageCleanupResult {
        val now = clockProvider.now()
        var deletedFilesCount = 0
        var cleanedTasksCount = 0

        for (task in tasks) {
            val paths = listOfNotNull(task.resultTxtPath, task.resultMdPath)

            if (paths.isEmpty()) {
                continue
            }

            for (rawPath in paths) {
                val resultPath = Path.of(rawPath)
                    .toAbsolutePath()
                    .normalize()

                if (!isInsideBasePath(resultPath, basePath)) {
                    log.warn(
                        "Skipping result file outside storage base path: taskId={}, path={}, basePath={}",
                        task.id,
                        resultPath,
                        basePath
                    )
                    continue
                }

                if (deleteResultFile(resultPath, task)) {
                    deletedFilesCount += 1
                }

                deleteEmptyTaskDirectory(resultPath.parent, basePath, task)
            }

            task.resultTxtPath = null
            task.resultMdPath = null
            task.updatedAt = now
            cleanedTasksCount += 1
        }

        return StorageCleanupResult(
            deletedFilesCount = deletedFilesCount,
            cleanedTasksCount = cleanedTasksCount
        )
    }

    private fun isInsideBasePath(
        path: Path,
        basePath: Path
    ): Boolean {
        return path.startsWith(basePath) && path != basePath
    }

    private fun deleteEmptyTaskDirectory(
        directory: Path?,
        basePath: Path,
        task: TranscriptionTask
    ) {
        if (directory == null || directory == basePath || !directory.startsWith(basePath)) {
            return
        }

        if (!Files.isDirectory(directory) || !isDirectoryEmpty(directory)) {
            return
        }

        try {
            Files.deleteIfExists(directory)
        } catch (ex: Exception) {
            log.warn(
                "Failed to delete empty result directory: taskId={}, directory={}",
                task.id,
                directory,
                ex
            )
        }
    }

    private fun deleteResultFile(
        resultPath: Path,
        task: TranscriptionTask
    ): Boolean {
        if (!Files.isRegularFile(resultPath)) {
            return false
        }

        return try {
            Files.deleteIfExists(resultPath)
        } catch (ex: Exception) {
            log.warn(
                "Failed to delete result file: taskId={}, path={}",
                task.id,
                resultPath,
                ex
            )
            false
        }
    }

    private fun isDirectoryEmpty(directory: Path): Boolean {
        return try {
            Files.list(directory).use { entries ->
                !entries.findAny().isPresent
            }
        } catch (ex: Exception) {
            log.warn("Failed to inspect result directory: directory={}", directory, ex)
            false
        }
    }
}

data class StorageCleanupResult(
    val deletedFilesCount: Int,
    val cleanedTasksCount: Int
)
