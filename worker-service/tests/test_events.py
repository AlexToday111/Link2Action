from uuid import UUID

from app.messaging.events import TranscriptionRequestedEvent


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
    assert event.source_url == "https://youtu.be/example"
    assert event.requested_formats == {"TXT", "MD"}
