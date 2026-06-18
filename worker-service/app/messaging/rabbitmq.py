import json
import logging
import time
from collections.abc import Callable
from typing import Any

import pika
from pika.adapters.blocking_connection import BlockingChannel
from pika.exceptions import AMQPConnectionError, AMQPError, UnroutableError
from pydantic import ValidationError

from app.config import Settings
from app.messaging.events import (
    TranscriptionRequestedEvent,
    TranscriptionResultEvent,
    TranscriptionStatus,
    extract_task_id,
)
from app.processing.processor import build_failed_event

log = logging.getLogger(__name__)

ProgressPublisher = Callable[[TranscriptionResultEvent], None]
MessageHandler = Callable[[TranscriptionRequestedEvent, ProgressPublisher], TranscriptionResultEvent]


class RabbitMqClient:
    def __init__(self, settings: Settings):
        self._settings = settings
        self._connection: pika.BlockingConnection | None = None
        self._channel: BlockingChannel | None = None

    def connect(self) -> None:
        credentials = pika.PlainCredentials(
            self._settings.rabbitmq_username,
            self._settings.rabbitmq_password,
        )
        connection_params = pika.ConnectionParameters(
            host=self._settings.rabbitmq_host,
            port=self._settings.rabbitmq_port,
            credentials=credentials,
            heartbeat=self._settings.rabbitmq_heartbeat,
            blocked_connection_timeout=self._settings.rabbitmq_blocked_connection_timeout,
        )

        attempts = self._settings.rabbitmq_connection_attempts
        for attempt in range(1, attempts + 1):
            try:
                self._connection = pika.BlockingConnection(connection_params)
                break
            except AMQPConnectionError:
                if attempt == attempts:
                    raise

                log.warning(
                    "RabbitMQ connection failed attempt=%s maxAttempts=%s host=%s port=%s",
                    attempt,
                    attempts,
                    self._settings.rabbitmq_host,
                    self._settings.rabbitmq_port,
                )
                time.sleep(self._settings.rabbitmq_connection_retry_seconds)

        if self._connection is None or self._connection.is_closed:
            raise AMQPConnectionError("RabbitMQ connection was not established")

        self._channel = self._connection.channel()
        self._declare_topology(self._channel)
        self._channel.basic_qos(prefetch_count=self._settings.rabbitmq_prefetch_count)
        self._channel.confirm_delivery()

        log.info(
            "Connected to RabbitMQ host=%s port=%s exchange=%s requestQueue=%s",
            self._settings.rabbitmq_host,
            self._settings.rabbitmq_port,
            self._settings.rabbitmq_exchange,
            self._settings.rabbitmq_request_queue,
        )

    def consume(self, handler: MessageHandler) -> None:
        channel = self._require_channel()

        def callback(
            ch: BlockingChannel,
            method: pika.spec.Basic.Deliver,
            properties: pika.BasicProperties,
            body: bytes,
        ) -> None:
            self._handle_delivery(ch, method, properties, body, handler)

        channel.basic_consume(
            queue=self._settings.rabbitmq_request_queue,
            on_message_callback=callback,
            auto_ack=False,
        )

        log.info("Waiting for transcription requests")
        channel.start_consuming()

    def close(self) -> None:
        if self._connection is not None and self._connection.is_open:
            self._connection.close()

    def publish_result(self, event: TranscriptionResultEvent) -> None:
        channel = self._get_publish_channel()
        routing_key = self._routing_key_for_status(event.status)

        try:
            published = channel.basic_publish(
                exchange=self._settings.rabbitmq_exchange,
                routing_key=routing_key,
                body=event.to_json_bytes(),
                properties=pika.BasicProperties(
                    content_type="application/json",
                    delivery_mode=2,
                ),
                mandatory=True,
            )
        except UnroutableError as exc:
            raise ResultPublishError("Result event was not routed to a queue") from exc
        except AMQPError as exc:
            raise ResultPublishError("RabbitMQ rejected result event publish") from exc
        except (OSError, RuntimeError) as exc:
            raise ResultPublishError("RabbitMQ connection was lost while publishing result event") from exc

        if published is False:
            raise ResultPublishError("RabbitMQ did not confirm result event publish")

        log.info(
            "Published result event taskId=%s status=%s routingKey=%s",
            event.task_id,
            event.status.value,
            routing_key,
        )

    def publish_progress(self, event: TranscriptionResultEvent) -> None:
        self.publish_result(event)

    def _handle_delivery(
        self,
        channel: BlockingChannel,
        method: pika.spec.Basic.Deliver,
        properties: pika.BasicProperties,
        body: bytes,
        handler: MessageHandler,
    ) -> None:
        try:
            event = self._parse_request(body)
        except InvalidTaskMessageError as exc:
            result = build_failed_event(
                task_id=exc.task_id,
                error_message="Invalid transcription request message",
            )

            try:
                self.publish_result(result)
            except ResultPublishError:
                log.exception("Failed to publish invalid-message result taskId=%s", exc.task_id)
                self._safe_nack(channel, method, task_id=exc.task_id)
                return

            self._safe_ack(channel, method, task_id=exc.task_id)
            return

        if event is None:
            self._safe_reject(channel, method, requeue=False)
            return

        log.info("Received transcription request taskId=%s", event.task_id)

        try:
            result = handler(event, self.publish_progress)
            self.publish_result(result)
        except ResultPublishError:
            log.exception("Failed to publish result event taskId=%s", event.task_id)
            self._safe_nack(channel, method, task_id=event.task_id)
            return
        except Exception:
            log.exception("Unexpected worker failure taskId=%s", event.task_id)
            self._safe_nack(channel, method, task_id=event.task_id)
            return

        self._safe_ack(channel, method, task_id=event.task_id)

    def _parse_request(self, body: bytes) -> TranscriptionRequestedEvent | None:
        try:
            payload: Any = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            log.exception("Received invalid JSON request message")
            return None

        try:
            return TranscriptionRequestedEvent.model_validate(payload)
        except ValidationError as exc:
            task_id = extract_task_id(payload)
            if task_id is None:
                log.error("Received invalid request message without usable taskId: %s", exc)
                return None

            raise InvalidTaskMessageError(task_id=task_id, message=str(exc)) from exc

    def _declare_topology(self, channel: BlockingChannel) -> None:
        channel.exchange_declare(
            exchange=self._settings.rabbitmq_exchange,
            exchange_type="direct",
            durable=True,
        )
        channel.queue_declare(queue=self._settings.rabbitmq_request_queue, durable=True)
        channel.queue_bind(
            queue=self._settings.rabbitmq_request_queue,
            exchange=self._settings.rabbitmq_exchange,
            routing_key=self._settings.rabbitmq_request_routing_key,
        )
        channel.queue_declare(queue=self._settings.rabbitmq_result_queue, durable=True)
        channel.queue_bind(
            queue=self._settings.rabbitmq_result_queue,
            exchange=self._settings.rabbitmq_exchange,
            routing_key=self._settings.rabbitmq_completed_routing_key,
        )
        channel.queue_bind(
            queue=self._settings.rabbitmq_result_queue,
            exchange=self._settings.rabbitmq_exchange,
            routing_key=self._settings.rabbitmq_failed_routing_key,
        )
        channel.queue_bind(
            queue=self._settings.rabbitmq_result_queue,
            exchange=self._settings.rabbitmq_exchange,
            routing_key=self._settings.rabbitmq_progress_routing_key,
        )

    def _routing_key_for_status(self, status: TranscriptionStatus) -> str:
        if status == TranscriptionStatus.COMPLETED:
            return self._settings.rabbitmq_completed_routing_key

        if status == TranscriptionStatus.FAILED:
            return self._settings.rabbitmq_failed_routing_key

        return self._settings.rabbitmq_progress_routing_key

    def _require_channel(self) -> BlockingChannel:
        if self._channel is None or self._channel.is_closed:
            raise RuntimeError("RabbitMQ channel is not connected")

        return self._channel

    def _get_publish_channel(self) -> BlockingChannel:
        if self._connection is None or self._connection.is_closed:
            raise ResultPublishError("RabbitMQ connection is closed")

        if self._channel is None or self._channel.is_closed:
            raise ResultPublishError("RabbitMQ channel is closed")

        return self._channel

    def _safe_ack(
        self,
        channel: BlockingChannel,
        method: pika.spec.Basic.Deliver,
        task_id: Any | None = None,
    ) -> None:
        if not channel.is_open:
            log.error("Cannot ack message because channel is closed taskId=%s", task_id)
            return

        try:
            channel.basic_ack(method.delivery_tag)
        except (AMQPError, OSError, RuntimeError):
            log.exception("Failed to ack message taskId=%s", task_id)

    def _safe_nack(
        self,
        channel: BlockingChannel,
        method: pika.spec.Basic.Deliver,
        task_id: Any | None = None,
    ) -> None:
        if not channel.is_open:
            log.error("Cannot nack message because channel is closed taskId=%s", task_id)
            return

        try:
            channel.basic_nack(method.delivery_tag, requeue=True)
        except (AMQPError, OSError, RuntimeError):
            log.exception("Failed to nack message taskId=%s", task_id)

    def _safe_reject(
        self,
        channel: BlockingChannel,
        method: pika.spec.Basic.Deliver,
        requeue: bool,
    ) -> None:
        if not channel.is_open:
            log.error("Cannot reject message because channel is closed")
            return

        try:
            channel.basic_reject(method.delivery_tag, requeue=requeue)
        except (AMQPError, OSError, RuntimeError):
            log.exception("Failed to reject message")


class ResultPublishError(RuntimeError):
    pass


class InvalidTaskMessageError(ValueError):
    def __init__(self, task_id: Any, message: str):
        super().__init__(message)
        self.task_id = task_id
