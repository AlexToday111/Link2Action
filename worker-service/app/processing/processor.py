import logging
import time
from collections.abc import Callable, Iterator
from contextlib import contextmanager

from app.common.time_utils import utc_now
from app.config import Settings
from app.messaging.events import (
    TranscriptionRequestedEvent,
    TranscriptionResultEvent,
    TranscriptionStatus,
)
from app.observability import record_stage_duration
from app.observability import record_task_processed
from app.processing.downloader import SourceDownloader
from app.processing.exporter import TranscriptExporter
from app.processing.transcriber import WhisperTranscriber

log = logging.getLogger(__name__)

ProgressPublisher = Callable[[TranscriptionResultEvent], None]


class TranscriptionProcessor:
    def __init__(
        self,
        settings: Settings,
        downloader: SourceDownloader,
        transcriber: WhisperTranscriber,
        exporter: TranscriptExporter,
    ):
        self._settings = settings
        self._downloader = downloader
        self._transcriber = transcriber
        self._exporter = exporter

    def process(
        self,
        event: TranscriptionRequestedEvent,
        progress_publisher: ProgressPublisher | None = None,
    ) -> TranscriptionResultEvent:
        task_id = event.task_id

        try:
            log.info("Downloading taskId=%s", task_id)
            publish_progress(
                progress_publisher=progress_publisher,
                task_id=task_id,
                status=TranscriptionStatus.DOWNLOADING,
            )
            with log_duration("download", task_id):
                downloaded = self._downloader.download(event)

            requested_language = event.language or self._settings.default_language
            log.info("Transcribing taskId=%s", task_id)
            publish_progress(
                progress_publisher=progress_publisher,
                task_id=task_id,
                status=TranscriptionStatus.TRANSCRIBING,
                title=downloaded.title,
                duration_seconds=downloaded.duration_seconds,
            )
            with log_duration("transcription", task_id):
                transcription = self._transcriber.transcribe(downloaded.path, requested_language)

            language = transcription.language or requested_language
            log.info("Exporting taskId=%s", task_id)
            publish_progress(
                progress_publisher=progress_publisher,
                task_id=task_id,
                status=TranscriptionStatus.EXPORTING,
                title=downloaded.title,
                duration_seconds=downloaded.duration_seconds,
                language=language,
            )
            with log_duration("export", task_id):
                exported = self._exporter.export(
                    task_id=task_id,
                    source_url=event.source_label,
                    title=downloaded.title,
                    duration_seconds=downloaded.duration_seconds,
                    language=language,
                    requested_formats=event.requested_formats,
                    transcription=transcription,
                    processing_mode=event.processing_mode,
                    source_type=event.source_type,
                    created_at=event.created_at.isoformat(),
                )

            log.info("Completed taskId=%s status=%s", task_id, TranscriptionStatus.COMPLETED.value)
            record_task_processed(TranscriptionStatus.COMPLETED.value)
            return TranscriptionResultEvent(
                taskId=task_id,
                status=TranscriptionStatus.COMPLETED,
                title=downloaded.title,
                durationSeconds=downloaded.duration_seconds,
                language=language,
                resultTxtPath=str(exported.txt_path) if exported.txt_path is not None else None,
                resultMdPath=str(exported.md_path) if exported.md_path is not None else None,
                resultPromptPath=str(exported.prompt_path) if exported.prompt_path is not None else None,
                resultPackagePath=str(exported.package_path) if exported.package_path is not None else None,
                errorMessage=None,
                completedAt=utc_now(),
            )
        except Exception as exc:
            log.exception("Failed taskId=%s status=%s", task_id, TranscriptionStatus.FAILED.value)
            return build_failed_event(
                task_id=task_id,
                error_message=human_readable_error(exc),
            )
        finally:
            try:
                self._downloader.cleanup(task_id)
            except Exception:
                log.exception("Failed to remove temporary downloads taskId=%s", task_id)


def build_failed_event(task_id, error_message: str) -> TranscriptionResultEvent:
    return TranscriptionResultEvent(
        taskId=task_id,
        status=TranscriptionStatus.FAILED,
        title=None,
        durationSeconds=None,
        language=None,
        resultTxtPath=None,
        resultMdPath=None,
        resultPromptPath=None,
        resultPackagePath=None,
        errorMessage=error_message,
        completedAt=utc_now(),
    )


def build_progress_event(
    task_id,
    status: TranscriptionStatus,
    title: str | None = None,
    duration_seconds: int | None = None,
    language: str | None = None,
) -> TranscriptionResultEvent:
    return TranscriptionResultEvent(
        taskId=task_id,
        status=status,
        title=title,
        durationSeconds=duration_seconds,
        language=language,
        resultTxtPath=None,
        resultMdPath=None,
        resultPromptPath=None,
        resultPackagePath=None,
        errorMessage=None,
        completedAt=None,
    )


def publish_progress(
    progress_publisher: ProgressPublisher | None,
    task_id,
    status: TranscriptionStatus,
    title: str | None = None,
    duration_seconds: int | None = None,
    language: str | None = None,
) -> None:
    if progress_publisher is None:
        return

    try:
        progress_publisher(
            build_progress_event(
                task_id=task_id,
                status=status,
                title=title,
                duration_seconds=duration_seconds,
                language=language,
            )
        )
    except Exception:
        log.exception("Failed to publish progress event taskId=%s status=%s", task_id, status.value)


@contextmanager
def log_duration(stage: str, task_id) -> Iterator[None]:
    started_at = time.monotonic()
    try:
        yield
    finally:
        duration_seconds = time.monotonic() - started_at
        record_stage_duration(stage, duration_seconds)
        log.info(
            "Worker stage duration taskId=%s stage=%s durationSeconds=%.3f",
            task_id,
            stage,
            duration_seconds,
        )


def human_readable_error(exc: Exception) -> str:
    message = str(exc).strip()
    if message:
        return message

    return exc.__class__.__name__
