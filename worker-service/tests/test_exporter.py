from uuid import UUID

from app.processing.exporter import TranscriptExporter
from app.processing.transcriber import TranscriptSegment, TranscriptionResult


def test_exporter_writes_txt_and_markdown(tmp_path):
    task_id = UUID("7f3a0f72-6e92-4e73-bdf7-efdf90c5aa31")
    exporter = TranscriptExporter(tmp_path)
    transcription = TranscriptionResult(
        text="Hello world.",
        language="en",
        segments=[
            TranscriptSegment(start=0, end=12, text="Hello world."),
        ],
    )

    exported = exporter.export(
        task_id=task_id,
        source_url="https://youtu.be/example",
        title="Video title",
        duration_seconds=842,
        language="en",
        requested_formats={"TXT", "MD"},
        transcription=transcription,
    )

    assert exported.txt_path is not None
    assert exported.md_path is not None
    assert exported.txt_path.name == "transcript.txt"
    assert exported.md_path.name == "transcript.md"
    assert "Title: Video title" in exported.txt_path.read_text(encoding="utf-8")
    assert "# Video title" in exported.md_path.read_text(encoding="utf-8")
    assert "**[00:00-00:12]** Hello world." in exported.md_path.read_text(encoding="utf-8")
