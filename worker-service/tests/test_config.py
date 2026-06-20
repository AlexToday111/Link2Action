from pathlib import Path

from app.config import Settings


def test_empty_yt_dlp_cookies_file_defaults_to_none():
    settings = Settings(yt_dlp_cookies_file="")

    assert settings.yt_dlp_cookies_file is None


def test_yt_dlp_cookies_file_accepts_path():
    settings = Settings(yt_dlp_cookies_file="/data/cookies/youtube.txt")

    assert settings.yt_dlp_cookies_file == Path("/data/cookies/youtube.txt")
