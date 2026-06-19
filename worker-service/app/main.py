import logging
import time

from pika.exceptions import AMQPError

from app.config import get_settings
from app.logging_config import configure_logging
from app.messaging.rabbitmq import RabbitMqClient
from app.observability import start_observability_server
from app.processing.downloader import AudioDownloader, SourceDownloader, TelegramFileDownloader
from app.processing.exporter import TranscriptExporter
from app.processing.processor import TranscriptionProcessor
from app.processing.transcriber import WhisperTranscriber

log = logging.getLogger(__name__)


def main() -> None:
    configure_logging()
    settings = get_settings()

    settings.results_base_path.mkdir(parents=True, exist_ok=True)
    settings.downloads_base_path.mkdir(parents=True, exist_ok=True)

    if settings.worker_metrics_enabled:
        start_observability_server(settings.worker_metrics_port)

    processor = TranscriptionProcessor(
        settings=settings,
        downloader=SourceDownloader(
            url_downloader=AudioDownloader(
                downloads_base_path=settings.downloads_base_path,
                max_duration_seconds=settings.max_video_duration_seconds,
            ),
            telegram_downloader=TelegramFileDownloader(settings),
        ),
        transcriber=WhisperTranscriber(
            model_name=settings.whisper_model,
            device=settings.whisper_device,
            compute_type=settings.whisper_compute_type,
        ),
        exporter=TranscriptExporter(results_base_path=settings.results_base_path),
    )

    while True:
        rabbitmq = RabbitMqClient(settings)

        try:
            rabbitmq.connect()
            rabbitmq.consume(processor.process)
        except KeyboardInterrupt:
            log.info("Worker stopped")
            break
        except (AMQPError, OSError, RuntimeError):
            log.exception(
                "RabbitMQ connection lost. Reconnecting in %s seconds",
                settings.rabbitmq_connection_retry_seconds,
            )
            time.sleep(settings.rabbitmq_connection_retry_seconds)
        finally:
            rabbitmq.close()


if __name__ == "__main__":
    main()
