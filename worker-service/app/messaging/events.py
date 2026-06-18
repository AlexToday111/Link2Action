from datetime import datetime
from enum import StrEnum
from typing import Any
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class TranscriptionStatus(StrEnum):
    DOWNLOADING = "DOWNLOADING"
    TRANSCRIBING = "TRANSCRIBING"
    EXPORTING = "EXPORTING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class TranscriptionRequestedEvent(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    task_id: UUID = Field(alias="taskId")
    source_url: str = Field(alias="sourceUrl", min_length=1)
    language: str | None = None
    formats: list[str] = Field(default_factory=list)
    created_at: datetime = Field(alias="createdAt")

    @property
    def requested_formats(self) -> set[str]:
        return {item.upper() for item in self.formats}


class TranscriptionResultEvent(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    task_id: UUID = Field(alias="taskId")
    status: TranscriptionStatus
    title: str | None = None
    duration_seconds: int | None = Field(default=None, alias="durationSeconds")
    language: str | None = None
    result_txt_path: str | None = Field(default=None, alias="resultTxtPath")
    result_md_path: str | None = Field(default=None, alias="resultMdPath")
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
