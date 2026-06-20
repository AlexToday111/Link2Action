from dataclasses import dataclass
from datetime import UTC, datetime
import json
from pathlib import Path
from uuid import UUID
from zipfile import ZIP_DEFLATED, ZipFile

from app.common.time_utils import format_duration, format_timestamp
from app.messaging.events import ProcessingMode, SourceType
from app.processing.transcriber import TranscriptionResult


@dataclass(frozen=True)
class ExportedFiles:
    txt_path: Path | None
    md_path: Path | None
    prompt_path: Path | None
    package_path: Path | None


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
        processing_mode: ProcessingMode = ProcessingMode.TRANSCRIPT,
        source_type: SourceType = SourceType.URL,
        created_at: str | None = None,
    ) -> ExportedFiles:
        formats = requested_formats or {"TXT", "MD"}
        supported_formats = formats & {"TXT", "MD", "PACKAGE"}

        if not supported_formats:
            raise ValueError("No supported result formats were requested")

        task_dir = self._results_base_path / str(task_id)
        task_dir.mkdir(parents=True, exist_ok=True)

        package_requested = "PACKAGE" in supported_formats
        txt_path = task_dir / "transcript.txt" if "TXT" in supported_formats or package_requested else None
        md_path = task_dir / "transcript.md" if "MD" in supported_formats or package_requested else None
        prompt_path = task_dir / "llm_prompt.txt"
        package_path = task_dir / "llm_package.zip" if package_requested else None

        if txt_path is not None:
            txt_path.write_text(
                self._build_txt(source_url, title, duration_seconds, language, transcription),
                encoding="utf-8",
            )

        if md_path is not None:
            md_path.write_text(
                self._build_markdown(
                    source_url,
                    title,
                    duration_seconds,
                    language,
                    processing_mode,
                    transcription,
                ),
                encoding="utf-8",
            )

        prompt_path.write_text(self._build_prompt(processing_mode), encoding="utf-8")

        if package_path is not None:
            self._build_package(
                package_path=package_path,
                task_id=task_id,
                source_url=source_url,
                source_type=source_type,
                title=title,
                duration_seconds=duration_seconds,
                language=language,
                processing_mode=processing_mode,
                created_at=created_at,
                txt_path=txt_path,
                md_path=md_path,
                prompt_path=prompt_path,
            )

        return ExportedFiles(
            txt_path=txt_path if "TXT" in supported_formats else None,
            md_path=md_path if "MD" in supported_formats else None,
            prompt_path=prompt_path,
            package_path=package_path,
        )

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
        processing_mode: ProcessingMode,
        transcription: TranscriptionResult,
    ) -> str:
        if processing_mode != ProcessingMode.TRANSCRIPT:
            return self._build_llm_ready_markdown(
                source_url,
                duration_seconds,
                language,
                processing_mode,
                transcription,
            )

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

    def _build_llm_ready_markdown(
        self,
        source_url: str,
        duration_seconds: int | None,
        language: str | None,
        processing_mode: ProcessingMode,
        transcription: TranscriptionResult,
    ) -> str:
        title = {
            ProcessingMode.SUMMARY: "Summary Request",
            ProcessingMode.ACTION_ITEMS: "Action Items Request",
            ProcessingMode.STUDY_NOTES: "Study Notes Request",
            ProcessingMode.TECH_TASKS: "Technical Tasks Request",
            ProcessingMode.CONTENT_REPURPOSE: "Content Repurpose Request",
        }[processing_mode]
        return "\n".join(
            [
                f"# {title}",
                "",
                f"- Source: {source_url}",
                f"- Language: {language or 'unknown'}",
                f"- Duration: {format_duration(duration_seconds)}",
                "",
                "## Ready-to-use LLM Prompt",
                "",
                self._build_prompt(processing_mode),
                "",
                "## Transcript",
                "",
                transcription.text,
                "",
            ]
        )

    def _build_prompt(self, processing_mode: ProcessingMode) -> str:
        prompts = {
            ProcessingMode.TRANSCRIPT: "Analyze this transcript and return summary, key points and action items.",
            ProcessingMode.SUMMARY: "\n".join(
                [
                    "Summarize the transcript below. Return:",
                    "1. Short summary",
                    "2. Key points",
                    "3. Important details",
                    "4. Open questions",
                ]
            ),
            ProcessingMode.ACTION_ITEMS: "\n".join(
                [
                    "Analyze the transcript below and extract action items.",
                    "",
                    "Return the result in Markdown:",
                    "",
                    "## Action Items",
                    "- [ ] ...",
                    "",
                    "## Decisions",
                    "- ...",
                    "",
                    "## Risks",
                    "- ...",
                    "",
                    "## Open Questions",
                    "- ...",
                ]
            ),
            ProcessingMode.STUDY_NOTES: "\n".join(
                [
                    "Create study notes from the transcript below.",
                    "",
                    "Return:",
                    "1. Topic overview",
                    "2. Key concepts",
                    "3. Definitions",
                    "4. Examples",
                    "5. Questions for self-check",
                    "6. Short revision checklist",
                ]
            ),
            ProcessingMode.TECH_TASKS: "\n".join(
                [
                    "Analyze the transcript below as a software engineering discussion.",
                    "",
                    "Return:",
                    "1. Backend tasks",
                    "2. Frontend tasks",
                    "3. DevOps tasks",
                    "4. Testing tasks",
                    "5. Risks",
                    "6. Acceptance criteria",
                ]
            ),
            ProcessingMode.CONTENT_REPURPOSE: "\n".join(
                [
                    "Turn the transcript below into content ideas.",
                    "",
                    "Return:",
                    "1. LinkedIn post",
                    "2. Telegram post",
                    "3. Twitter/X thread",
                    "4. Short article outline",
                    "5. 5 hooks",
                    "6. 5 title ideas",
                ]
            ),
        }
        return prompts[processing_mode]

    def _build_package(
        self,
        package_path: Path,
        task_id: UUID,
        source_url: str,
        source_type: SourceType,
        title: str | None,
        duration_seconds: int | None,
        language: str | None,
        processing_mode: ProcessingMode,
        created_at: str | None,
        txt_path: Path | None,
        md_path: Path | None,
        prompt_path: Path,
    ) -> None:
        metadata = {
            "taskId": str(task_id),
            "title": title,
            "sourceType": source_type.value,
            "sourceUrl": source_url,
            "language": language,
            "durationSeconds": duration_seconds,
            "processingMode": processing_mode.value,
            "createdAt": created_at,
            "generatedAt": datetime.now(UTC).isoformat(),
        }
        readme = "\n".join(
            [
                "# Link2Action LLM Package",
                "",
                "This package contains:",
                "- transcript.md",
                "- transcript.txt",
                "- llm_prompt.txt",
                "- metadata.json",
                "",
                "How to use:",
                "1. Open your preferred LLM.",
                "2. Upload transcript.md or paste llm_prompt.txt.",
                "3. Ask the LLM to process the transcript.",
                "",
            ]
        )

        with ZipFile(package_path, mode="w", compression=ZIP_DEFLATED) as archive:
            if md_path is not None:
                archive.write(md_path, "transcript.md")
            if txt_path is not None:
                archive.write(txt_path, "transcript.txt")
            archive.write(prompt_path, "llm_prompt.txt")
            archive.writestr("README.md", readme)
            archive.writestr("metadata.json", json.dumps(metadata, indent=2))
