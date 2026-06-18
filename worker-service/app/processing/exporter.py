from dataclasses import dataclass
from pathlib import Path
from uuid import UUID

from app.common.time_utils import format_duration, format_timestamp
from app.processing.transcriber import TranscriptionResult


@dataclass(frozen=True)
class ExportedFiles:
    txt_path: Path | None
    md_path: Path | None


class TranscriptExporter:
    def __init__(self, results_base_path: Path):
        self._results_base_path = results_base_path

    def export(
        self,
        task_id: UUID,
        source_url: str,
        title: str | None,
        duration_seconds: int | None,
        language: str | None,
        requested_formats: set[str],
        transcription: TranscriptionResult,
    ) -> ExportedFiles:
        formats = requested_formats or {"TXT", "MD"}
        supported_formats = formats & {"TXT", "MD"}

        if not supported_formats:
            raise ValueError("No supported result formats were requested")

        task_dir = self._results_base_path / str(task_id)
        task_dir.mkdir(parents=True, exist_ok=True)

        txt_path = task_dir / "transcript.txt" if "TXT" in supported_formats else None
        md_path = task_dir / "transcript.md" if "MD" in supported_formats else None

        if txt_path is not None:
            txt_path.write_text(
                self._build_txt(source_url, title, duration_seconds, language, transcription),
                encoding="utf-8",
            )

        if md_path is not None:
            md_path.write_text(
                self._build_markdown(source_url, title, duration_seconds, language, transcription),
                encoding="utf-8",
            )

        return ExportedFiles(txt_path=txt_path, md_path=md_path)

    def _build_txt(
        self,
        source_url: str,
        title: str | None,
        duration_seconds: int | None,
        language: str | None,
        transcription: TranscriptionResult,
    ) -> str:
        return "\n".join(
            [
                f"Title: {title or 'Untitled'}",
                f"Source: {source_url}",
                f"Language: {language or 'unknown'}",
                f"Duration: {format_duration(duration_seconds)}",
                "",
                transcription.text,
                "",
            ]
        )

    def _build_markdown(
        self,
        source_url: str,
        title: str | None,
        duration_seconds: int | None,
        language: str | None,
        transcription: TranscriptionResult,
    ) -> str:
        lines = [
            f"# {title or 'Untitled'}",
            "",
            f"- Source: {source_url}",
            f"- Language: {language or 'unknown'}",
            f"- Duration: {format_duration(duration_seconds)}",
            "",
            "## Transcript",
            "",
            transcription.text,
            "",
            "## Segments",
            "",
        ]

        for segment in transcription.segments:
            start = format_timestamp(segment.start)
            end = format_timestamp(segment.end)
            lines.append(f"- **[{start}-{end}]** {segment.text}")

        lines.append("")
        return "\n".join(lines)
