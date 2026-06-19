from pathlib import Path
from uuid import UUID

from app.config import Settings
from app.messaging.events import TranscriptionRequestedEvent, TranscriptionStatus
from app.processing.downloader import DownloadedAudio
from app.processing.exporter import ExportedFiles
from app.processing.processor import TranscriptionProcessor
from app.processing.transcriber import TranscriptSegment, TranscriptionResult

TASK_ID = UUID("7f3a0f72-6e92-4e73-bdf7-efdf90c5aa31")


class FakeDownloader:
    def __init__(self, should_fail: bool = False):
        self.should_fail = should_fail
        self.cleanup_calls = []
        self.download_calls = []

    def download(self, event):
        self.download_calls.append(event)
        if self.should_fail:
            raise RuntimeError("download failed")

        return DownloadedAudio(
            path=Path("/tmp/audio.mp3"),
            title="Video title",
            duration_seconds=120,
        )

    def cleanup(self, task_id):
        self.cleanup_calls.append(task_id)


class FakeTranscriber:
    def __init__(self, should_fail: bool = False):
        self.should_fail = should_fail
        self.calls = []

    def transcribe(self, audio_path, language):
        self.calls.append((audio_path, language))
        if self.should_fail:
            raise RuntimeError("transcribe failed")

        return TranscriptionResult(
            text="Hello world.",
            language="en",
            segments=[
                TranscriptSegment(start=0, end=1, text="Hello world."),
            ],
        )


class FakeExporter:
    def __init__(self, should_fail: bool = False):
        self.should_fail = should_fail
        self.calls = []

    def export(self, **kwargs):
        self.calls.append(kwargs)
        if self.should_fail:
            raise RuntimeError("export failed")

        return ExportedFiles(
            txt_path=Path("/data/results/task/transcript.txt"),
            md_path=Path("/data/results/task/transcript.md"),
        )


def test_processor_successful_pipeline_publishes_progress_and_cleans_up():
    downloader = FakeDownloader()
    transcriber = FakeTranscriber()
    exporter = FakeExporter()
    processor = make_processor(downloader, transcriber, exporter)
    progress_events = []

    result = processor.process(make_event(), progress_events.append)

    assert result.status == TranscriptionStatus.COMPLETED
    assert result.title == "Video title"
    assert result.duration_seconds == 120
    assert result.language == "en"
    assert result.result_txt_path == "/data/results/task/transcript.txt"
    assert result.result_md_path == "/data/results/task/transcript.md"
    assert [event.status for event in progress_events] == [
        TranscriptionStatus.DOWNLOADING,
        TranscriptionStatus.TRANSCRIBING,
        TranscriptionStatus.EXPORTING,
    ]
    assert transcriber.calls == [(Path("/tmp/audio.mp3"), None)]
    assert exporter.calls[0]["requested_formats"] == {"TXT", "MD"}
    assert downloader.cleanup_calls == [TASK_ID]


def test_processor_download_failure_returns_failed_and_cleans_up():
    downloader = FakeDownloader(should_fail=True)
    processor = make_processor(downloader, FakeTranscriber(), FakeExporter())

    result = processor.process(make_event(), lambda event: None)

    assert result.status == TranscriptionStatus.FAILED
    assert result.error_message == "download failed"
    assert downloader.cleanup_calls == [TASK_ID]


def test_processor_transcribe_failure_returns_failed_and_cleans_up():
    downloader = FakeDownloader()
    processor = make_processor(downloader, FakeTranscriber(should_fail=True), FakeExporter())

    result = processor.process(make_event(), lambda event: None)

    assert result.status == TranscriptionStatus.FAILED
    assert result.error_message == "transcribe failed"
    assert downloader.cleanup_calls == [TASK_ID]


def test_processor_export_failure_returns_failed_and_cleans_up():
    downloader = FakeDownloader()
    processor = make_processor(downloader, FakeTranscriber(), FakeExporter(should_fail=True))

    result = processor.process(make_event(), lambda event: None)

    assert result.status == TranscriptionStatus.FAILED
    assert result.error_message == "export failed"
    assert downloader.cleanup_calls == [TASK_ID]


def test_processor_progress_publisher_failure_does_not_break_processing():
    downloader = FakeDownloader()
    processor = make_processor(downloader, FakeTranscriber(), FakeExporter())

    def failing_progress_publisher(event):
        raise RuntimeError("progress publish failed")

    result = processor.process(make_event(), failing_progress_publisher)

    assert result.status == TranscriptionStatus.COMPLETED
    assert downloader.cleanup_calls == [TASK_ID]


def make_processor(downloader, transcriber, exporter) -> TranscriptionProcessor:
    return TranscriptionProcessor(
        settings=Settings(whisper_language=None),
        downloader=downloader,
        transcriber=transcriber,
        exporter=exporter,
    )


def make_event() -> TranscriptionRequestedEvent:
    return TranscriptionRequestedEvent.model_validate(
        {
            "taskId": str(TASK_ID),
            "sourceUrl": "https://youtu.be/example",
            "language": None,
            "formats": ["TXT", "MD"],
            "createdAt": "2026-06-18T15:30:00Z",
        }
    )
