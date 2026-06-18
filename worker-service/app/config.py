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

    @property
    def default_language(self) -> str | None:
        if self.whisper_language is None:
            return None

        value = self.whisper_language.strip()
        return value or None


def get_settings() -> Settings:
    return Settings()
