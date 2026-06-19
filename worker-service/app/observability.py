import logging
import threading
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    from prometheus_client import CONTENT_TYPE_LATEST
    from prometheus_client import Counter
    from prometheus_client import Histogram
    from prometheus_client import generate_latest

    PROMETHEUS_AVAILABLE = True
except ImportError:
    CONTENT_TYPE_LATEST = "text/plain; version=0.0.4; charset=utf-8"
    Counter = None
    Histogram = None
    generate_latest = None
    PROMETHEUS_AVAILABLE = False

log = logging.getLogger(__name__)

TASKS_PROCESSED = (
    Counter(
        "link2action_worker_tasks_processed_total",
        "Total worker tasks processed",
        ["status"],
    )
    if PROMETHEUS_AVAILABLE
    else None
)
TASKS_FAILED = (
    Counter(
        "link2action_worker_tasks_failed_total",
        "Total worker tasks failed",
    )
    if PROMETHEUS_AVAILABLE
    else None
)
DOWNLOAD_DURATION = (
    Histogram(
        "link2action_worker_download_duration_seconds",
        "Worker download stage duration",
    )
    if PROMETHEUS_AVAILABLE
    else None
)
TRANSCRIPTION_DURATION = (
    Histogram(
        "link2action_worker_transcription_duration_seconds",
        "Worker transcription stage duration",
    )
    if PROMETHEUS_AVAILABLE
    else None
)
EXPORT_DURATION = (
    Histogram(
        "link2action_worker_export_duration_seconds",
        "Worker export stage duration",
    )
    if PROMETHEUS_AVAILABLE
    else None
)

STAGE_DURATION_METRICS = {
    "download": DOWNLOAD_DURATION,
    "transcription": TRANSCRIPTION_DURATION,
    "export": EXPORT_DURATION,
}


def start_observability_server(port: int) -> ThreadingHTTPServer | None:
    server = ThreadingHTTPServer(("0.0.0.0", port), ObservabilityHandler)
    thread = threading.Thread(target=server.serve_forever, name="observability-http", daemon=True)
    thread.start()

    if PROMETHEUS_AVAILABLE:
        log.info("Worker observability server started port=%s", port)
    else:
        log.warning(
            "Worker observability server started without prometheus_client; /metrics will return 503 port=%s",
            port,
        )

    return server


def record_task_processed(status: str) -> None:
    log.info("Worker task counter status=%s", status)
    if TASKS_PROCESSED is not None:
        TASKS_PROCESSED.labels(status=status).inc()


def record_task_failed() -> None:
    if TASKS_FAILED is not None:
        TASKS_FAILED.inc()


def record_stage_duration(stage: str, duration_seconds: float) -> None:
    metric = STAGE_DURATION_METRICS.get(stage)
    if metric is not None:
        metric.observe(duration_seconds)


class ObservabilityHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        if self.path == "/health":
            self._write_response(
                status=HTTPStatus.OK,
                content_type="application/json",
                body=b'{"status":"UP"}\n',
            )
            return

        if self.path == "/metrics":
            if generate_latest is None:
                self._write_response(
                    status=HTTPStatus.SERVICE_UNAVAILABLE,
                    content_type="text/plain; charset=utf-8",
                    body=b"prometheus_client is not installed\n",
                )
                return

            self._write_response(
                status=HTTPStatus.OK,
                content_type=CONTENT_TYPE_LATEST,
                body=generate_latest(),
            )
            return

        self._write_response(
            status=HTTPStatus.NOT_FOUND,
            content_type="text/plain; charset=utf-8",
            body=b"not found\n",
        )

    def log_message(self, format: str, *args) -> None:
        log.debug("Worker observability http " + format, *args)

    def _write_response(
        self,
        status: HTTPStatus,
        content_type: str,
        body: bytes,
    ) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
