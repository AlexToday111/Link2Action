from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_prefix="",
        extra="ignore",
        populate_by_name=True,
    )

    rabbitmq_host: str = Field("rabbitmq", validation_alias="RABBITMQ_HOST")
    rabbitmq_port: int = Field(5672, validation_alias="RABBITMQ_PORT")
    rabbitmq_username: str = Field("guest", validation_alias="RABBITMQ_USERNAME")
    rabbitmq_password: str = Field("guest", validation_alias="RABBITMQ_PASSWORD")

    rabbitmq_exchange: str = Field("linkscribe.exchange", validation_alias="RABBITMQ_EXCHANGE")
    rabbitmq_request_queue: str = Field(
        "linkscribe.transcription.requests",
        validation_alias="RABBITMQ_REQUEST_QUEUE",
    )
    rabbitmq_request_retry_queue: str = Field(
        "linkscribe.transcription.requests.retry",
        validation_alias="RABBITMQ_REQUEST_RETRY_QUEUE",
    )
    rabbitmq_request_dlq: str = Field(
        "linkscribe.transcription.requests.dlq",
        validation_alias="RABBITMQ_REQUEST_DLQ",
    )
    rabbitmq_result_queue: str = Field(
        "linkscribe.transcription.results",
        validation_alias="RABBITMQ_RESULT_QUEUE",
    )
    rabbitmq_request_routing_key: str = Field(
        "transcription.requested",
        validation_alias="RABBITMQ_REQUEST_ROUTING_KEY",
    )
    rabbitmq_completed_routing_key: str = Field(
        "transcription.completed",
        validation_alias="RABBITMQ_COMPLETED_ROUTING_KEY",
    )
    rabbitmq_failed_routing_key: str = Field(
        "transcription.failed",
        validation_alias="RABBITMQ_FAILED_ROUTING_KEY",
    )
    rabbitmq_progress_routing_key: str = Field(
        "transcription.progress",
        validation_alias="RABBITMQ_PROGRESS_ROUTING_KEY",
    )
    rabbitmq_retry_routing_key: str = Field(
        "transcription.requested.retry",
        validation_alias="RABBITMQ_RETRY_ROUTING_KEY",
    )
    rabbitmq_dlq_routing_key: str = Field(
        "transcription.requested.dlq",
        validation_alias="RABBITMQ_DLQ_ROUTING_KEY",
    )
    rabbitmq_retry_delay_ms: int = Field(30000, validation_alias="RABBITMQ_RETRY_DELAY_MS")
    rabbitmq_max_retry_attempts: int = Field(
        3,
        validation_alias="RABBITMQ_MAX_RETRY_ATTEMPTS",
    )
    rabbitmq_heartbeat: int = Field(0, validation_alias="RABBITMQ_HEARTBEAT")
    rabbitmq_blocked_connection_timeout: int = Field(
        300,
        validation_alias="RABBITMQ_BLOCKED_CONNECTION_TIMEOUT",
    )
    rabbitmq_prefetch_count: int = Field(1, validation_alias="RABBITMQ_PREFETCH_COUNT")
    rabbitmq_connection_attempts: int = Field(30, validation_alias="RABBITMQ_CONNECTION_ATTEMPTS")
    rabbitmq_connection_retry_seconds: float = Field(
        2.0,
        validation_alias="RABBITMQ_CONNECTION_RETRY_SECONDS",
    )

    results_base_path: Path = Field(Path("/data/results"), validation_alias="RESULTS_BASE_PATH")
    downloads_base_path: Path = Field(Path("/data/downloads"), validation_alias="DOWNLOADS_BASE_PATH")

    whisper_model: str = Field("small", validation_alias="WHISPER_MODEL")
    whisper_device: str = Field("cpu", validation_alias="WHISPER_DEVICE")
    whisper_compute_type: str = Field("int8", validation_alias="WHISPER_COMPUTE_TYPE")
    whisper_language: str | None = Field(None, validation_alias="WHISPER_LANGUAGE")

    max_video_duration_seconds: int = Field(
        3600,
        validation_alias="MAX_VIDEO_DURATION_SECONDS",
    )
    worker_metrics_enabled: bool = Field(True, validation_alias="WORKER_METRICS_ENABLED")
    worker_metrics_port: int = Field(9091, validation_alias="WORKER_METRICS_PORT")

    telegram_bot_token: str | None = Field(None, validation_alias="TELEGRAM_BOT_TOKEN")
    telegram_api_base_url: str = Field(
        "https://api.telegram.org",
        validation_alias="TELEGRAM_API_BASE_URL",
    )
    telegram_file_download_base_url: str = Field(
        "https://api.telegram.org/file",
        validation_alias="TELEGRAM_FILE_DOWNLOAD_BASE_URL",
    )
    telegram_file_download_timeout_seconds: int = Field(
        60,
        validation_alias="TELEGRAM_FILE_DOWNLOAD_TIMEOUT_SECONDS",
    )

    @property
    def default_language(self) -> str | None:
        if self.whisper_language is None:
            return None

        value = self.whisper_language.strip()
        return value or None


def get_settings() -> Settings:
    return Settings()
