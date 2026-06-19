from uuid import UUID

import pytest
from yt_dlp.utils import DownloadError

from app.processing.downloader import AudioDownloader, VideoTooLongError

TASK_ID = UUID("7f3a0f72-6e92-4e73-bdf7-efdf90c5aa31")


class FakeYoutubeDL:
    metadata = {"title": "Video title", "duration": 120}
    fail_extract = False
    fail_download = False
    instances = []

    def __init__(self, options):
        self.options = options
        self.downloaded_urls = []
        FakeYoutubeDL.instances.append(self)

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False

    def extract_info(self, source_url, download):
        if FakeYoutubeDL.fail_extract:
            raise DownloadError("metadata failed")

        return FakeYoutubeDL.metadata

    def download(self, urls):
        if FakeYoutubeDL.fail_download:
            raise DownloadError("download failed")

        self.downloaded_urls.extend(urls)


@pytest.fixture(autouse=True)
def reset_fake_youtube_dl(monkeypatch):
    FakeYoutubeDL.metadata = {"title": "Video title", "duration": 120}
    FakeYoutubeDL.fail_extract = False
    FakeYoutubeDL.fail_download = False
    FakeYoutubeDL.instances = []
    monkeypatch.setattr("app.processing.downloader.YoutubeDL", FakeYoutubeDL)


def test_extract_metadata_success(tmp_path):
    downloader = AudioDownloader(tmp_path, max_duration_seconds=3600)

    metadata = downloader.extract_metadata("https://youtu.be/example")

    assert metadata.title == "Video title"
    assert metadata.duration_seconds == 120
    assert FakeYoutubeDL.instances[0].options["skip_download"] is True


def test_too_long_video_raises(tmp_path):
    FakeYoutubeDL.metadata = {"title": "Long video", "duration": 7200}
    downloader = AudioDownloader(tmp_path, max_duration_seconds=3600)

    with pytest.raises(VideoTooLongError, match="Video is too long"):
        downloader.download(TASK_ID, "https://youtu.be/example")


def test_download_failure_returns_readable_error(tmp_path):
    FakeYoutubeDL.fail_download = True
    downloader = AudioDownloader(tmp_path, max_duration_seconds=3600)

    with pytest.raises(RuntimeError, match="audio download failed"):
        downloader.download(TASK_ID, "https://youtu.be/example")


def test_missing_audio_file_after_download_raises(tmp_path):
    downloader = AudioDownloader(tmp_path, max_duration_seconds=3600)

    with pytest.raises(RuntimeError, match="no audio file"):
        downloader.download(TASK_ID, "https://youtu.be/example")


def test_download_returns_created_audio_file(tmp_path):
    class CreatingYoutubeDL(FakeYoutubeDL):
        def download(self, urls):
            super().download(urls)
            task_dir = tmp_path / str(TASK_ID)
            (task_dir / "audio.mp3").write_bytes(b"mp3")

    downloader = AudioDownloader(tmp_path, max_duration_seconds=3600)

    with pytest.MonkeyPatch.context() as monkeypatch:
        monkeypatch.setattr("app.processing.downloader.YoutubeDL", CreatingYoutubeDL)
        downloaded = downloader.download(TASK_ID, "https://youtu.be/example")

    assert downloaded.path == tmp_path / str(TASK_ID) / "audio.mp3"
    assert downloaded.title == "Video title"
    assert downloaded.duration_seconds == 120


def test_cleanup_removes_task_download_directory(tmp_path):
    downloader = AudioDownloader(tmp_path, max_duration_seconds=3600)
    task_dir = downloader.task_download_dir(TASK_ID)
    task_dir.mkdir(parents=True)
    (task_dir / "audio.mp3").write_bytes(b"mp3")

    downloader.cleanup(TASK_ID)

    assert not task_dir.exists()
