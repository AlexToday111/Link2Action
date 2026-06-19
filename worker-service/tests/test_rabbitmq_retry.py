import json
from types import SimpleNamespace
from uuid import UUID

import pika
import pytest

from app.config import Settings
from app.messaging.events import TranscriptionResultEvent, TranscriptionStatus
from app.messaging.rabbitmq import (
    RETRY_COUNT_HEADER,
    RabbitMqClient,
    build_retry_headers,
    increment_retry_count,
    is_retry_allowed,
    read_retry_count,
)


TASK_ID = UUID("7f3a0f72-6e92-4e73-bdf7-efdf90c5aa31")


class FakeConnection:
    is_closed = False


class FakeChannel:
    is_open = True
    is_closed = False

    def __init__(self):
        self.acks = []
        self.nacks = []
        self.rejects = []
        self.publishes = []

    def basic_ack(self, delivery_tag):
        self.acks.append(delivery_tag)

    def basic_nack(self, delivery_tag, requeue):
        self.nacks.append((delivery_tag, requeue))

    def basic_reject(self, delivery_tag, requeue):
        self.rejects.append((delivery_tag, requeue))

    def basic_publish(self, exchange, routing_key, body, properties, mandatory):
        self.publishes.append(
            {
                "exchange": exchange,
                "routing_key": routing_key,
                "body": body,
                "properties": properties,
                "mandatory": mandatory,
            }
        )
        return True

    def exchange_declare(self, exchange, exchange_type, durable):
        pass

    def queue_declare(self, queue, durable, arguments=None):
        pass

    def queue_bind(self, queue, exchange, routing_key):
        pass


def make_client(settings: Settings | None = None) -> tuple[RabbitMqClient, FakeChannel]:
    client = RabbitMqClient(settings or Settings())
    channel = FakeChannel()
    client._connection = FakeConnection()
    client._channel = channel
    return client, channel


def make_method():
    return SimpleNamespace(delivery_tag=42)


def make_properties(retry_count: int | None = None):
    headers = {}
    if retry_count is not None:
        headers[RETRY_COUNT_HEADER] = retry_count

    return pika.BasicProperties(content_type="application/json", headers=headers)


def make_request_body() -> bytes:
    return json.dumps(
        {
            "taskId": str(TASK_ID),
            "sourceUrl": "https://youtu.be/example",
            "language": None,
            "formats": ["TXT", "MD"],
            "createdAt": "2026-06-18T15:30:00Z",
        }
    ).encode("utf-8")


def completed_event() -> TranscriptionResultEvent:
    return TranscriptionResultEvent(
        taskId=TASK_ID,
        status=TranscriptionStatus.COMPLETED,
        resultTxtPath="/data/results/task/transcript.txt",
    )


def failed_event(message: str = "processing failed") -> TranscriptionResultEvent:
    return TranscriptionResultEvent(
        taskId=TASK_ID,
        status=TranscriptionStatus.FAILED,
        errorMessage=message,
    )


def test_read_retry_count_defaults_to_zero_without_header():
    assert read_retry_count({}) == 0
    assert read_retry_count(None) == 0


def test_read_retry_count_defaults_to_zero_for_invalid_header():
    assert read_retry_count({RETRY_COUNT_HEADER: "invalid"}) == 0
    assert read_retry_count({RETRY_COUNT_HEADER: -1}) == 0


def test_build_retry_headers_preserves_existing_headers_and_sets_retry_count():
    headers = build_retry_headers({"correlation": "abc"}, 2)

    assert headers == {"correlation": "abc", RETRY_COUNT_HEADER: 2}


def test_retry_count_increments():
    assert increment_retry_count(0) == 1
    assert increment_retry_count(2) == 3
    assert increment_retry_count(-5) == 1


def test_retry_allowed_before_max_and_denied_at_max():
    assert is_retry_allowed(retry_count=2, max_retry_attempts=3) is True
    assert is_retry_allowed(retry_count=3, max_retry_attempts=3) is False


def test_invalid_json_rejected_without_requeue():
    client, channel = make_client()

    client._handle_delivery(
        channel=channel,
        method=make_method(),
        properties=make_properties(),
        body=b"{not-json",
        handler=pytest.fail,
    )

    assert channel.rejects == [(42, False)]
    assert channel.acks == []
    assert channel.nacks == []
    assert channel.publishes == []


def test_invalid_message_with_task_id_publishes_failed_event():
    client, channel = make_client()
    body = json.dumps({"taskId": str(TASK_ID)}).encode("utf-8")

    client._handle_delivery(
        channel=channel,
        method=make_method(),
        properties=make_properties(),
        body=body,
        handler=pytest.fail,
    )

    assert channel.acks == [42]
    assert channel.rejects == []
    assert channel.nacks == []
    assert len(channel.publishes) == 1
    publish = channel.publishes[0]
    assert publish["routing_key"] == "transcription.failed"
    payload = json.loads(publish["body"].decode("utf-8"))
    assert payload["taskId"] == str(TASK_ID)
    assert payload["status"] == "FAILED"


def test_processing_error_schedules_retry_while_retry_count_below_max():
    client, channel = make_client()

    def failing_handler(event, progress_publisher):
        raise RuntimeError("temporary failure")

    client._handle_delivery(
        channel=channel,
        method=make_method(),
        properties=make_properties(retry_count=1),
        body=make_request_body(),
        handler=failing_handler,
    )

    assert channel.acks == [42]
    assert channel.rejects == []
    assert channel.nacks == []
    assert len(channel.publishes) == 1
    publish = channel.publishes[0]
    assert publish["routing_key"] == "transcription.requested.retry"
    assert publish["properties"].headers[RETRY_COUNT_HEADER] == 2


def test_failed_result_schedules_retry_while_retry_count_below_max():
    client, channel = make_client()

    def handler(event, progress_publisher):
        return failed_event("temporary failure")

    client._handle_delivery(
        channel=channel,
        method=make_method(),
        properties=make_properties(retry_count=1),
        body=make_request_body(),
        handler=handler,
    )

    assert channel.acks == [42]
    assert channel.rejects == []
    assert channel.nacks == []
    assert len(channel.publishes) == 1
    publish = channel.publishes[0]
    assert publish["routing_key"] == "transcription.requested.retry"
    assert publish["properties"].headers[RETRY_COUNT_HEADER] == 2


def test_processing_error_goes_to_dlq_when_retry_count_reaches_max():
    settings = Settings(rabbitmq_max_retry_attempts=3)
    client, channel = make_client(settings)

    def failing_handler(event, progress_publisher):
        raise RuntimeError("permanent failure")

    client._handle_delivery(
        channel=channel,
        method=make_method(),
        properties=make_properties(retry_count=3),
        body=make_request_body(),
        handler=failing_handler,
    )

    assert channel.acks == []
    assert channel.nacks == []
    assert channel.rejects == [(42, False)]
    assert len(channel.publishes) == 1
    publish = channel.publishes[0]
    assert publish["routing_key"] == "transcription.failed"
    payload = json.loads(publish["body"].decode("utf-8"))
    assert payload["taskId"] == str(TASK_ID)
    assert payload["status"] == "FAILED"
    assert payload["errorMessage"] == "permanent failure"


def test_failed_result_goes_to_dlq_when_retry_count_reaches_max():
    settings = Settings(rabbitmq_max_retry_attempts=3)
    client, channel = make_client(settings)

    def handler(event, progress_publisher):
        return failed_event("permanent failure")

    client._handle_delivery(
        channel=channel,
        method=make_method(),
        properties=make_properties(retry_count=3),
        body=make_request_body(),
        handler=handler,
    )

    assert channel.acks == []
    assert channel.nacks == []
    assert channel.rejects == [(42, False)]
    assert len(channel.publishes) == 1
    publish = channel.publishes[0]
    assert publish["routing_key"] == "transcription.failed"
    payload = json.loads(publish["body"].decode("utf-8"))
    assert payload["taskId"] == str(TASK_ID)
    assert payload["status"] == "FAILED"
    assert payload["errorMessage"] == "permanent failure"


def test_successful_processing_acks_message():
    client, channel = make_client()

    def handler(event, progress_publisher):
        return completed_event()

    client._handle_delivery(
        channel=channel,
        method=make_method(),
        properties=make_properties(),
        body=make_request_body(),
        handler=handler,
    )

    assert channel.acks == [42]
    assert channel.nacks == []
    assert channel.rejects == []
    assert len(channel.publishes) == 1
    assert channel.publishes[0]["routing_key"] == "transcription.completed"
