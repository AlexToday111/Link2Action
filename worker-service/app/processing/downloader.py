import logging
import shutil
from dataclasses import dataclass
from pathlib import Path
from uuid import UUID

from yt_dlp import YoutubeDL
from yt_dlp.utils import DownloadError

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class VideoMetadata:
    title: str | None
    duration_seconds: int | None


@dataclass(frozen=True)
class DownloadedAudio:
    path: Path
    title: str | None
    duration_seconds: int | None


class VideoTooLongError(ValueError):
    pass


class AudioDownloader:
    def __init__(self, downloads_base_path: Path, max_duration_seconds: int):
        self._downloads_base_path = downloads_base_path
        self._max_duration_seconds = max_duration_seconds

    def download(self, task_id: UUID, source_url: str) -> DownloadedAudio:
        task_dir = self.task_download_dir(task_id)
        task_dir.mkdir(parents=True, exist_ok=True)

        metadata = self.extract_metadata(source_url)
        duration = metadata.duration_seconds

        if duration is not None and duration > self._max_duration_seconds:
            raise VideoTooLongError(
                f"Video is too long: {duration} seconds. Maximum allowed duration is "
                f"{self._max_duration_seconds} seconds."
            )

        log.info("Downloading audio taskId=%s", task_id)
        options = {
            "format": "bestaudio/best",
            "outtmpl": str(task_dir / "%(id)s.%(ext)s"),
            "noplaylist": True,
            "quiet": True,
            "no_warnings": True,
            "postprocessors": [
                {
                    "key": "FFmpegExtractAudio",
                    "preferredcodec": "mp3",
                    "preferredquality": "192",
                }
            ],
        }

        try:
            with YoutubeDL(options) as ydl:
                ydl.download([source_url])
        except DownloadError as exc:
            raise RuntimeError("Video is unavailable or audio download failed") from exc

        audio_path = self._find_downloaded_audio(task_dir)
        if audio_path is None:
            raise RuntimeError("Audio download completed but no audio file was created")

        return DownloadedAudio(
            path=audio_path,
            title=metadata.title,
            duration_seconds=metadata.duration_seconds,
        )

    def extract_metadata(self, source_url: str) -> VideoMetadata:
        options = {
            "skip_download": True,
            "noplaylist": True,
            "quiet": True,
            "no_warnings": True,
        }

        try:
            with YoutubeDL(options) as ydl:
                info = ydl.extract_info(source_url, download=False)
        except DownloadError as exc:
            raise RuntimeError("Video is unavailable or metadata could not be read") from exc

        duration = info.get("duration")
        duration_seconds = int(duration) if duration is not None else None
        title = info.get("title")

        return VideoMetadata(
            title=str(title) if title else None,
            duration_seconds=duration_seconds,
        )

    def cleanup(self, task_id: UUID) -> None:
        task_dir = self.task_download_dir(task_id)
        if task_dir.exists():
            shutil.rmtree(task_dir)

    def task_download_dir(self, task_id: UUID) -> Path:
        return self._downloads_base_path / str(task_id)

    def _find_downloaded_audio(self, task_dir: Path) -> Path | None:
        candidates = [
            path
            for path in task_dir.iterdir()
            if path.is_file() and path.suffix.lower() in {".mp3", ".m4a", ".webm", ".opus", ".wav"}
        ]

        if not candidates:
            return None

        return sorted(candidates)[0]
