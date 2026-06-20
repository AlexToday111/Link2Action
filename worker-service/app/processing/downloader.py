import logging
import json
import mimetypes
import shutil
import subprocess
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from uuid import UUID

from app.config import Settings
from app.messaging.events import SourceType, TranscriptionRequestedEvent
from yt_dlp import YoutubeDL
from yt_dlp.utils import DownloadError

log = logging.getLogger(__name__)

YOUTUBE_COOKIES_HINT = (
    "YouTube requires authentication cookies for this video. Export browser cookies "
    "to a Netscape cookies.txt file and set YT_DLP_COOKIES_FILE to the mounted path."
)


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
    def __init__(
        self,
        downloads_base_path: Path,
        max_duration_seconds: int,
        cookies_file: Path | None = None,
    ):
        self._downloads_base_path = downloads_base_path
        self._max_duration_seconds = max_duration_seconds
        self._cookies_file = cookies_file

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
        options = self._base_options()
        options.update(
            {
                "format": "bestaudio/best",
                "outtmpl": str(task_dir / "%(id)s.%(ext)s"),
                "postprocessors": [
                    {
                        "key": "FFmpegExtractAudio",
                        "preferredcodec": "mp3",
                        "preferredquality": "192",
                    }
                ],
            }
        )

        try:
            with YoutubeDL(options) as ydl:
                ydl.download([source_url])
        except DownloadError as exc:
            raise RuntimeError(
                self._download_error_message(exc, "Video is unavailable or audio download failed")
            ) from exc

        audio_path = self._find_downloaded_audio(task_dir)
        if audio_path is None:
            raise RuntimeError("Audio download completed but no audio file was created")

        return DownloadedAudio(
            path=audio_path,
            title=metadata.title,
            duration_seconds=metadata.duration_seconds,
        )

    def extract_metadata(self, source_url: str) -> VideoMetadata:
        options = self._base_options()
        options.update({"skip_download": True})

        try:
            with YoutubeDL(options) as ydl:
                info = ydl.extract_info(source_url, download=False)
        except DownloadError as exc:
            raise RuntimeError(
                self._download_error_message(
                    exc,
                    "Video is unavailable or metadata could not be read",
                )
            ) from exc

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

    def _base_options(self) -> dict:
        options = {
            "noplaylist": True,
            "quiet": True,
            "no_warnings": True,
        }

        if self._cookies_file is not None:
            if not self._cookies_file.exists():
                raise RuntimeError(
                    f"Configured yt-dlp cookies file does not exist: {self._cookies_file}"
                )

            options["cookiefile"] = str(self._cookies_file)

        return options

    def _download_error_message(self, exc: DownloadError, fallback: str) -> str:
        message = str(exc).lower()
        if "sign in to confirm" in message or "not a bot" in message:
            return YOUTUBE_COOKIES_HINT

        return fallback

    def _find_downloaded_audio(self, task_dir: Path) -> Path | None:
        candidates = [
            path
            for path in task_dir.iterdir()
            if path.is_file() and path.suffix.lower() in {".mp3", ".m4a", ".webm", ".opus", ".wav"}
        ]

        if not candidates:
            return None

        return sorted(candidates)[0]


class SourceDownloader:
    def __init__(
        self,
        url_downloader: AudioDownloader,
        telegram_downloader: "TelegramFileDownloader",
    ):
        self._url_downloader = url_downloader
        self._telegram_downloader = telegram_downloader

    def download(self, event: TranscriptionRequestedEvent) -> DownloadedAudio:
        if event.source_type == SourceType.URL:
            return self._url_downloader.download(event.task_id, event.source_url or "")

        if event.source_type == SourceType.TELEGRAM_FILE:
            return self._telegram_downloader.download(event)

        raise ValueError(f"Unsupported source type: {event.source_type}")

    def cleanup(self, task_id: UUID) -> None:
        self._url_downloader.cleanup(task_id)


class TelegramFileDownloader:
    def __init__(self, settings: Settings):
        self._settings = settings

    def download(self, event: TranscriptionRequestedEvent) -> DownloadedAudio:
        if not self._settings.telegram_bot_token:
            raise RuntimeError("Telegram bot token is required to download uploaded media")

        task_dir = self.task_download_dir(event.task_id)
        task_dir.mkdir(parents=True, exist_ok=True)

        file_path = self.get_file_path(event.telegram_file_id or "")
        source_path = self.download_file(
            task_dir=task_dir,
            file_path=file_path,
            original_file_name=event.original_file_name,
            mime_type=event.mime_type,
        )
        audio_path = task_dir / "audio.wav"
        extract_audio_with_ffmpeg(source_path, audio_path)

        return DownloadedAudio(
            path=audio_path,
            title=event.original_file_name or "Telegram file",
            duration_seconds=None,
        )

    def get_file_path(self, telegram_file_id: str) -> str:
        url = (
            f"{self._settings.telegram_api_base_url.rstrip('/')}"
            f"/bot{self._settings.telegram_bot_token}/getFile?file_id={telegram_file_id}"
        )

        with urllib.request.urlopen(
            url,
            timeout=self._settings.telegram_file_download_timeout_seconds,
        ) as response:
            payload = json.loads(response.read().decode("utf-8"))

        if not payload.get("ok"):
            raise RuntimeError("Telegram getFile returned non-ok response")

        file_path = payload.get("result", {}).get("file_path")
        if not file_path:
            raise RuntimeError("Telegram getFile did not return file_path")

        return str(file_path)

    def download_file(
        self,
        task_dir: Path,
        file_path: str,
        original_file_name: str | None,
        mime_type: str | None,
    ) -> Path:
        suffix = Path(original_file_name or file_path).suffix
        if not suffix:
            suffix = mimetypes.guess_extension(mime_type or "") or ".bin"

        destination = task_dir / f"telegram-source{suffix}"
        url = (
            f"{self._settings.telegram_file_download_base_url.rstrip('/')}"
            f"/bot{self._settings.telegram_bot_token}/{file_path.lstrip('/')}"
        )

        with urllib.request.urlopen(
            url,
            timeout=self._settings.telegram_file_download_timeout_seconds,
        ) as response:
            destination.write_bytes(response.read())

        return destination

    def task_download_dir(self, task_id: UUID) -> Path:
        return self._settings.downloads_base_path / str(task_id)


def build_ffmpeg_extract_audio_command(input_path: Path, output_path: Path) -> list[str]:
    return [
        "ffmpeg",
        "-y",
        "-i",
        str(input_path),
        "-vn",
        "-acodec",
        "pcm_s16le",
        "-ar",
        "16000",
        "-ac",
        "1",
        str(output_path),
    ]


def extract_audio_with_ffmpeg(input_path: Path, output_path: Path) -> None:
    command = build_ffmpeg_extract_audio_command(input_path, output_path)

    try:
        subprocess.run(
            command,
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        raise RuntimeError("Could not extract audio from uploaded media") from exc
