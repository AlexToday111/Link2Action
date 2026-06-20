from datetime import datetime
from enum import StrEnum
from typing import Any
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator


class TranscriptionStatus(StrEnum):
    DOWNLOADING = "DOWNLOADING"
    TRANSCRIBING = "TRANSCRIBING"
    EXPORTING = "EXPORTING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class SourceType(StrEnum):
    URL = "URL"
    TELEGRAM_FILE = "TELEGRAM_FILE"


class ProcessingMode(StrEnum):
    TRANSCRIPT = "TRANSCRIPT"
    SUMMARY = "SUMMARY"
    ACTION_ITEMS = "ACTION_ITEMS"
    STUDY_NOTES = "STUDY_NOTES"
    TECH_TASKS = "TECH_TASKS"
    CONTENT_REPURPOSE = "CONTENT_REPURPOSE"


class TranscriptionRequestedEvent(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    task_id: UUID = Field(alias="taskId")
    source_type: SourceType = Field(default=SourceType.URL, alias="sourceType")
    source_url: str | None = Field(default=None, alias="sourceUrl")
    telegram_file_id: str | None = Field(default=None, alias="telegramFileId")
    telegram_file_unique_id: str | None = Field(default=None, alias="telegramFileUniqueId")
    original_file_name: str | None = Field(default=None, alias="originalFileName")
    mime_type: str | None = Field(default=None, alias="mimeType")
    file_size_bytes: int | None = Field(default=None, alias="fileSizeBytes")
    processing_mode: ProcessingMode = Field(default=ProcessingMode.TRANSCRIPT, alias="processingMode")
    language: str | None = None
    formats: list[str] = Field(default_factory=list)
    created_at: datetime = Field(alias="createdAt")

    @model_validator(mode="after")
    def validate_source(self) -> "TranscriptionRequestedEvent":
        if self.source_type == SourceType.URL and not (self.source_url or "").strip():
            raise ValueError("sourceUrl is required for URL transcription source")

        if self.source_type == SourceType.TELEGRAM_FILE and not (self.telegram_file_id or "").strip():
            raise ValueError("telegramFileId is required for Telegram file transcription source")

        return self

    @property
    def requested_formats(self) -> set[str]:
        return {item.upper() for item in self.formats}

    @property
    def source_label(self) -> str:
        if self.source_type == SourceType.URL:
            return self.source_url or ""

        return self.original_file_name or self.mime_type or "Telegram file"


class TranscriptionResultEvent(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    task_id: UUID = Field(alias="taskId")
    status: TranscriptionStatus
    title: str | None = None
    duration_seconds: int | None = Field(default=None, alias="durationSeconds")
    language: str | None = None
    result_txt_path: str | None = Field(default=None, alias="resultTxtPath")
    result_md_path: str | None = Field(default=None, alias="resultMdPath")
    result_prompt_path: str | None = Field(default=None, alias="resultPromptPath")
    result_package_path: str | None = Field(default=None, alias="resultPackagePath")
    error_message: str | None = Field(default=None, alias="errorMessage")
    completed_at: datetime | None = Field(default=None, alias="completedAt")

    def to_json_bytes(self) -> bytes:
        return self.model_dump_json(by_alias=True).encode("utf-8")


def extract_task_id(payload: Any) -> UUID | None:
    if not isinstance(payload, dict):
        return None

    value = payload.get("taskId")
    if value is None:
        return None

    try:
        return UUID(str(value))
    except ValueError:
        return None
