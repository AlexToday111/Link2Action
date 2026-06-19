from pathlib import Path
from types import SimpleNamespace

from app.processing.transcriber import WhisperTranscriber


class FakeWhisperModel:
    instances = []

    def __init__(self, model_name, device, compute_type):
        self.model_name = model_name
        self.device = device
        self.compute_type = compute_type
        self.calls = []
        FakeWhisperModel.instances.append(self)

    def transcribe(self, audio_path, language, vad_filter):
        self.calls.append(
            {
                "audio_path": audio_path,
                "language": language,
                "vad_filter": vad_filter,
            }
        )
        return (
            [
                SimpleNamespace(start=0.0, end=1.5, text=" Hello "),
                SimpleNamespace(start=1.5, end=3.0, text="world "),
            ],
            SimpleNamespace(language="en"),
        )


def test_transcriber_lazy_loads_model_once(monkeypatch):
    FakeWhisperModel.instances = []
    monkeypatch.setattr("app.processing.transcriber.WhisperModel", FakeWhisperModel)
    transcriber = WhisperTranscriber("small", "cpu", "int8")

    transcriber.transcribe(Path("/tmp/audio.mp3"), language="en")
    transcriber.transcribe(Path("/tmp/audio.mp3"), language="en")

    assert len(FakeWhisperModel.instances) == 1
    assert FakeWhisperModel.instances[0].model_name == "small"
    assert FakeWhisperModel.instances[0].device == "cpu"
    assert FakeWhisperModel.instances[0].compute_type == "int8"


def test_transcriber_passes_language_to_model(monkeypatch):
    FakeWhisperModel.instances = []
    monkeypatch.setattr("app.processing.transcriber.WhisperModel", FakeWhisperModel)
    transcriber = WhisperTranscriber("small", "cpu", "int8")

    transcriber.transcribe(Path("/tmp/audio.mp3"), language="ru")

    assert FakeWhisperModel.instances[0].calls[0] == {
        "audio_path": "/tmp/audio.mp3",
        "language": "ru",
        "vad_filter": True,
    }


def test_transcriber_collects_segments_into_result(monkeypatch):
    FakeWhisperModel.instances = []
    monkeypatch.setattr("app.processing.transcriber.WhisperModel", FakeWhisperModel)
    transcriber = WhisperTranscriber("small", "cpu", "int8")

    result = transcriber.transcribe(Path("/tmp/audio.mp3"), language=None)

    assert result.language == "en"
    assert result.text == "Hello\nworld"
    assert len(result.segments) == 2
    assert result.segments[0].text == "Hello"
    assert result.segments[1].text == "world"


def test_transcriber_handles_empty_segments(monkeypatch):
    class EmptyWhisperModel(FakeWhisperModel):
        def transcribe(self, audio_path, language, vad_filter):
            return ([], SimpleNamespace(language="en"))

    monkeypatch.setattr("app.processing.transcriber.WhisperModel", EmptyWhisperModel)
    transcriber = WhisperTranscriber("small", "cpu", "int8")

    result = transcriber.transcribe(Path("/tmp/audio.mp3"), language=None)

    assert result.text == ""
    assert result.language == "en"
    assert result.segments == []
