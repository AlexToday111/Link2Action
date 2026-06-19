import io
import json
from pathlib import Path
from uuid import UUID

import pytest

from app.config import Settings
from app.messaging.events import TranscriptionRequestedEvent
from app.processing.downloader import (
    DownloadedAudio,
    SourceDownloader,
    TelegramFileDownloader,
    build_ffmpeg_extract_audio_command,
    extract_audio_with_ffmpeg,
)

TASK_ID = UUID("7f3a0f72-6e92-4e73-bdf7-efdf90c5aa31")


class FakeUrlDownloader:
    def __init__(self):
        self.download_calls = []
        self.cleanup_calls = []

    def download(self, task_id, source_url):
        self.download_calls.append((task_id, source_url))
        return DownloadedAudio(Path("/tmp/url.mp3"), "URL title", 120)

    def cleanup(self, task_id):
        self.cleanup_calls.append(task_id)


class FakeTelegramDownloader:
    def __init__(self):
        self.download_calls = []

    def download(self, event):
        self.download_calls.append(event)
        return DownloadedAudio(Path("/tmp/telegram.wav"), "Telegram file", None)


def test_source_downloader_uses_url_downloader_for_url_event():
    url_downloader = FakeUrlDownloader()
    telegram_downloader = FakeTelegramDownloader()
    downloader = SourceDownloader(url_downloader, telegram_downloader)

    downloaded = downloader.download(make_url_event())

    assert downloaded.path == Path("/tmp/url.mp3")
    assert url_downloader.download_calls == [(TASK_ID, "https://youtu.be/example")]
    assert telegram_downloader.download_calls == []


def test_source_downloader_uses_telegram_downloader_for_file_event():
    url_downloader = FakeUrlDownloader()
    telegram_downloader = FakeTelegramDownloader()
    downloader = SourceDownloader(url_downloader, telegram_downloader)

    downloaded = downloader.download(make_telegram_event())

    assert downloaded.path == Path("/tmp/telegram.wav")
    assert telegram_downloader.download_calls[0].telegram_file_id == "file-id"
    assert url_downloader.download_calls == []


def test_telegram_get_file_response_is_parsed(monkeypatch, tmp_path):
    downloader = TelegramFileDownloader(make_settings(tmp_path))

    def fake_urlopen(url, timeout):
        assert "getFile" in url
        return FakeResponse({"ok": True, "result": {"file_path": "videos/file.mp4"}})

    monkeypatch.setattr("app.processing.downloader.urllib.request.urlopen", fake_urlopen)

    assert downloader.get_file_path("file-id") == "videos/file.mp4"


def test_telegram_file_download_saves_to_task_directory(monkeypatch, tmp_path):
    downloader = TelegramFileDownloader(make_settings(tmp_path))
    task_dir = tmp_path / str(TASK_ID)
    task_dir.mkdir()

    def fake_urlopen(url, timeout):
        assert "/file/bottoken/" in url
        return io.BytesIO(b"media")

    monkeypatch.setattr("app.processing.downloader.urllib.request.urlopen", fake_urlopen)

    path = downloader.download_file(task_dir, "voice/file.oga", "voice.ogg", "audio/ogg")

    assert path == task_dir / "telegram-source.ogg"
    assert path.read_bytes() == b"media"


def test_ffmpeg_extract_audio_command():
    command = build_ffmpeg_extract_audio_command(Path("/tmp/in.mp4"), Path("/tmp/out.wav"))

    assert command == [
        "ffmpeg",
        "-y",
        "-i",
        "/tmp/in.mp4",
        "-vn",
        "-acodec",
        "pcm_s16le",
        "-ar",
        "16000",
        "-ac",
        "1",
        "/tmp/out.wav",
    ]


def test_ffmpeg_failure_returns_readable_error(monkeypatch):
    def fake_run(*args, **kwargs):
        raise OSError("ffmpeg missing")

    monkeypatch.setattr("app.processing.downloader.subprocess.run", fake_run)

    with pytest.raises(RuntimeError, match="Could not extract audio"):
        extract_audio_with_ffmpeg(Path("/tmp/in.mp4"), Path("/tmp/out.wav"))


def make_settings(tmp_path):
    return Settings(
        downloads_base_path=tmp_path,
        telegram_bot_token="token",
        telegram_api_base_url="https://api.telegram.org",
        telegram_file_download_base_url="https://api.telegram.org/file",
    )


def make_url_event():
    return TranscriptionRequestedEvent.model_validate(
        {
            "taskId": str(TASK_ID),
            "sourceUrl": "https://youtu.be/example",
            "language": None,
            "formats": ["TXT"],
            "createdAt": "2026-06-18T15:30:00Z",
        }
    )


def make_telegram_event():
    return TranscriptionRequestedEvent.model_validate(
        {
            "taskId": str(TASK_ID),
            "sourceType": "TELEGRAM_FILE",
            "telegramFileId": "file-id",
            "telegramFileUniqueId": "unique-id",
            "originalFileName": "lecture.mp4",
            "mimeType": "video/mp4",
            "language": None,
            "formats": ["TXT"],
            "createdAt": "2026-06-18T15:30:00Z",
        }
    )


class FakeResponse:
    def __init__(self, payload):
        self._payload = payload

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False

    def read(self):
        return json.dumps(self._payload).encode("utf-8")
