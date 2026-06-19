from uuid import UUID

import pytest
from pydantic import ValidationError

from app.messaging.events import SourceType, TranscriptionRequestedEvent


def test_transcription_requested_event_parses_bot_payload():
    event = TranscriptionRequestedEvent.model_validate(
        {
            "taskId": "7f3a0f72-6e92-4e73-bdf7-efdf90c5aa31",
            "sourceUrl": "https://youtu.be/example",
            "language": None,
            "formats": ["TXT", "MD"],
            "createdAt": "2026-06-18T15:30:00Z",
        }
    )

    assert event.task_id == UUID("7f3a0f72-6e92-4e73-bdf7-efdf90c5aa31")
    assert event.source_type == SourceType.URL
    assert event.source_url == "https://youtu.be/example"
    assert event.requested_formats == {"TXT", "MD"}


def test_transcription_requested_event_parses_telegram_file_payload():
    event = TranscriptionRequestedEvent.model_validate(
        {
            "taskId": "7f3a0f72-6e92-4e73-bdf7-efdf90c5aa31",
            "sourceType": "TELEGRAM_FILE",
            "sourceUrl": None,
            "telegramFileId": "file-id",
            "telegramFileUniqueId": "unique-id",
            "originalFileName": "lecture.mp4",
            "mimeType": "video/mp4",
            "fileSizeBytes": 18400000,
            "language": None,
            "formats": ["TXT"],
            "createdAt": "2026-06-18T15:30:00Z",
        }
    )

    assert event.source_type == SourceType.TELEGRAM_FILE
    assert event.telegram_file_id == "file-id"
    assert event.source_label == "lecture.mp4"


def test_telegram_file_event_requires_file_id():
    with pytest.raises(ValidationError, match="telegramFileId"):
        TranscriptionRequestedEvent.model_validate(
            {
                "taskId": "7f3a0f72-6e92-4e73-bdf7-efdf90c5aa31",
                "sourceType": "TELEGRAM_FILE",
                "language": None,
                "formats": ["TXT"],
                "createdAt": "2026-06-18T15:30:00Z",
            }
        )


def test_old_url_event_without_source_type_remains_supported():
    event = TranscriptionRequestedEvent.model_validate(
        {
            "taskId": "7f3a0f72-6e92-4e73-bdf7-efdf90c5aa31",
            "sourceUrl": "https://youtu.be/old",
            "language": None,
            "formats": [],
            "createdAt": "2026-06-18T15:30:00Z",
        }
    )

    assert event.source_type == SourceType.URL
    assert event.source_url == "https://youtu.be/old"
