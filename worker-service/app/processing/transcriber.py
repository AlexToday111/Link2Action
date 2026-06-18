import logging
from dataclasses import dataclass
from pathlib import Path

from faster_whisper import WhisperModel

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class TranscriptSegment:
    start: float
    end: float
    text: str


@dataclass(frozen=True)
class TranscriptionResult:
    text: str
    language: str | None
    segments: list[TranscriptSegment]


class WhisperTranscriber:
    def __init__(self, model_name: str, device: str, compute_type: str):
        self._model_name = model_name
        self._device = device
        self._compute_type = compute_type
        self._model: WhisperModel | None = None

    def transcribe(self, audio_path: Path, language: str | None) -> TranscriptionResult:
        log.info("Transcribing audio path=%s", audio_path)
        model = self._get_model()
        segments_iter, info = model.transcribe(
            str(audio_path),
            language=language,
            vad_filter=True,
        )

        segments = [
            TranscriptSegment(
                start=segment.start,
                end=segment.end,
                text=segment.text.strip(),
            )
            for segment in segments_iter
        ]
        full_text = "\n".join(segment.text for segment in segments if segment.text)

        return TranscriptionResult(
            text=full_text,
            language=getattr(info, "language", None),
            segments=segments,
        )

    def _get_model(self) -> WhisperModel:
        if self._model is None:
            log.info(
                "Loading Whisper model model=%s device=%s computeType=%s",
                self._model_name,
                self._device,
                self._compute_type,
            )
            self._model = WhisperModel(
                self._model_name,
                device=self._device,
                compute_type=self._compute_type,
            )

        return self._model
