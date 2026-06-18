import logging

from app.common.time_utils import utc_now
from app.config import Settings
from app.messaging.events import (
    TranscriptionRequestedEvent,
    TranscriptionResultEvent,
    TranscriptionStatus,
)
from app.processing.downloader import AudioDownloader
from app.processing.exporter import TranscriptExporter
from app.processing.transcriber import WhisperTranscriber

log = logging.getLogger(__name__)


class TranscriptionProcessor:
    def __init__(
        self,
        settings: Settings,
        downloader: AudioDownloader,
        transcriber: WhisperTranscriber,
        exporter: TranscriptExporter,
    ):
        self._settings = settings
        self._downloader = downloader
        self._transcriber = transcriber
        self._exporter = exporter

    def process(self, event: TranscriptionRequestedEvent) -> TranscriptionResultEvent:
        task_id = event.task_id

        try:
            log.info("Downloading taskId=%s", task_id)
            downloaded = self._downloader.download(task_id, event.source_url)

            requested_language = event.language or self._settings.default_language
            log.info("Transcribing taskId=%s", task_id)
            transcription = self._transcriber.transcribe(downloaded.path, requested_language)

            language = transcription.language or requested_language
            log.info("Exporting taskId=%s", task_id)
            exported = self._exporter.export(
                task_id=task_id,
                source_url=event.source_url,
                title=downloaded.title,
                duration_seconds=downloaded.duration_seconds,
                language=language,
                requested_formats=event.requested_formats,
                transcription=transcription,
            )

            log.info("Completed taskId=%s", task_id)
            return TranscriptionResultEvent(
                taskId=task_id,
                status=TranscriptionStatus.COMPLETED,
                title=downloaded.title,
                durationSeconds=downloaded.duration_seconds,
                language=language,
                resultTxtPath=str(exported.txt_path) if exported.txt_path is not None else None,
                resultMdPath=str(exported.md_path) if exported.md_path is not None else None,
                errorMessage=None,
                completedAt=utc_now(),
            )
        except Exception as exc:
            log.exception("Failed taskId=%s", task_id)
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
        errorMessage=error_message,
        completedAt=utc_now(),
    )


def human_readable_error(exc: Exception) -> str:
    message = str(exc).strip()
    if message:
        return message

    return exc.__class__.__name__
