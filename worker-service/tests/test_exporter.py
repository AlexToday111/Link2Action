from uuid import UUID

import pytest

from app.processing.exporter import TranscriptExporter
from app.processing.transcriber import TranscriptSegment, TranscriptionResult

TASK_ID = UUID("7f3a0f72-6e92-4e73-bdf7-efdf90c5aa31")


def test_exporter_writes_txt_and_markdown(tmp_path):
    exporter = TranscriptExporter(tmp_path)

    exported = exporter.export(
        task_id=TASK_ID,
        source_url="https://youtu.be/example",
        title="Video title",
        duration_seconds=842,
        language="en",
        requested_formats={"TXT", "MD"},
        transcription=sample_transcription(),
    )

    assert exported.txt_path is not None
    assert exported.md_path is not None
    assert exported.txt_path.name == "transcript.txt"
    assert exported.md_path.name == "transcript.md"
    assert "Title: Video title" in exported.txt_path.read_text(encoding="utf-8")
    assert "# Video title" in exported.md_path.read_text(encoding="utf-8")
    assert "**[00:00-00:12]** Hello world." in exported.md_path.read_text(encoding="utf-8")


def test_exporter_writes_txt_only(tmp_path):
    exporter = TranscriptExporter(tmp_path)

    exported = exporter.export(
        task_id=TASK_ID,
        source_url="https://youtu.be/example",
        title="Video title",
        duration_seconds=842,
        language="en",
        requested_formats={"TXT"},
        transcription=sample_transcription(),
    )

    assert exported.txt_path is not None
    assert exported.md_path is None
    assert exported.txt_path.name == "transcript.txt"
    assert "Hello world." in exported.txt_path.read_text(encoding="utf-8")


def test_exporter_writes_markdown_only_with_metadata_and_segments(tmp_path):
    exporter = TranscriptExporter(tmp_path)

    exported = exporter.export(
        task_id=TASK_ID,
        source_url="https://youtu.be/example",
        title="Video title",
        duration_seconds=842,
        language="en",
        requested_formats={"MD"},
        transcription=sample_transcription(),
    )

    assert exported.txt_path is None
    assert exported.md_path is not None
    content = exported.md_path.read_text(encoding="utf-8")
    assert "# Video title" in content
    assert "- Source: https://youtu.be/example" in content
    assert "- Language: en" in content
    assert "- Duration: 14:02" in content
    assert "**[00:00-00:12]** Hello world." in content


def test_exporter_defaults_to_txt_and_markdown_when_formats_empty(tmp_path):
    exporter = TranscriptExporter(tmp_path)

    exported = exporter.export(
        task_id=TASK_ID,
        source_url="https://youtu.be/example",
        title="Video title",
        duration_seconds=842,
        language="en",
        requested_formats=set(),
        transcription=sample_transcription(),
    )

    assert exported.txt_path is not None
    assert exported.md_path is not None


def test_exporter_raises_when_formats_are_unsupported(tmp_path):
    exporter = TranscriptExporter(tmp_path)

    with pytest.raises(ValueError, match="No supported result formats"):
        exporter.export(
            task_id=TASK_ID,
            source_url="https://youtu.be/example",
            title="Video title",
            duration_seconds=842,
            language="en",
            requested_formats={"PDF"},
            transcription=sample_transcription(),
        )


def test_txt_export_contains_full_transcript(tmp_path):
    exporter = TranscriptExporter(tmp_path)

    exported = exporter.export(
        task_id=TASK_ID,
        source_url="https://youtu.be/example",
        title="Video title",
        duration_seconds=842,
        language="en",
        requested_formats={"TXT"},
        transcription=sample_transcription(),
    )

    assert exported.txt_path is not None
    assert "Hello world." in exported.txt_path.read_text(encoding="utf-8")


def sample_transcription() -> TranscriptionResult:
    return TranscriptionResult(
        text="Hello world.",
        language="en",
        segments=[
            TranscriptSegment(start=0, end=12, text="Hello world."),
        ],
    )
