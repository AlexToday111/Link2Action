# Link2Action

Telegram bot for transcribing videos from links.

## Reliability

Transcription requests are delivered through RabbitMQ with a bounded retry flow:

- main queue: `linkscribe.transcription.requests`
- retry queue: `linkscribe.transcription.requests.retry`
- dead-letter queue: `linkscribe.transcription.requests.dlq`

Worker failures are retried by publishing the original message to the retry queue with an
`x-retry-count` header. The retry queue uses TTL and dead-letters the message back to the main
request routing key. After `RABBITMQ_MAX_RETRY_ATTEMPTS`, the worker publishes a final `FAILED`
event and rejects the original message without requeue so RabbitMQ routes it to the DLQ.

The bot service also prevents duplicate active transcription tasks. For the same Telegram user,
trimmed source URL, requested format, and normalized language, an active `QUEUED` or `PROCESSING`
task is reused instead of creating another task. Completed or failed tasks do not block a new
request with the same parameters.

## Observability

`bot-service` exposes Spring Boot Actuator endpoints:

- `GET /actuator/health`
- `GET /actuator/info`
- `GET /actuator/metrics`
- `GET /actuator/prometheus`

Business metrics include task created/completed/failed counters, active task gauge, task duration
timer, and RabbitMQ result event counters.

`worker-service` exposes a lightweight HTTP endpoint when `WORKER_METRICS_ENABLED=true`:

- `GET /health`
- `GET /metrics`

The worker also logs stage durations for download, transcription, and export with `taskId`, stage,
and duration fields.

## Environment

RabbitMQ retry/DLQ settings:

| Variable | Default |
| --- | --- |
| `RABBITMQ_REQUEST_RETRY_QUEUE` | `linkscribe.transcription.requests.retry` |
| `RABBITMQ_REQUEST_DLQ` | `linkscribe.transcription.requests.dlq` |
| `RABBITMQ_RETRY_ROUTING_KEY` | `transcription.requested.retry` |
| `RABBITMQ_DLQ_ROUTING_KEY` | `transcription.requested.dlq` |
| `RABBITMQ_RETRY_DELAY_MS` | `30000` |
| `RABBITMQ_MAX_RETRY_ATTEMPTS` | `3` |

Worker observability settings:

| Variable | Default |
| --- | --- |
| `WORKER_METRICS_ENABLED` | `true` |
| `WORKER_METRICS_PORT` | `9091` |

## Development checks

Run bot-service tests:

```bash
cd bot-service
./gradlew clean test
```

Run worker-service tests:

```bash
cd worker-service
pytest
```

Validate and build Docker Compose:

```bash
docker compose config
docker compose build
```

## Docker notes

Local development uses `docker-compose.yml` and keeps PostgreSQL, RabbitMQ AMQP, RabbitMQ
management, bot-service, and worker metrics ports published for convenient debugging.

Copy `.env.example` to `.env` and set at least `TELEGRAM_BOT_TOKEN` before running:

```bash
docker compose up --build
```

For a more production-oriented local shape, layer the production override:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up --build
```

The production override removes the PostgreSQL host port, keeps RabbitMQ management private,
adds restart policies, and applies basic log rotation. Service images run application processes as
non-root users.

## CI

GitHub Actions runs on pushes and pull requests to `main`.

Jobs:

- `bot-service tests`: Java 21, Gradle cache, `./gradlew clean test`.
- `worker-service tests`: Python 3.11, pip cache, `pip install -r requirements.txt`, `pytest`.
- `docker compose`: `docker compose config` and `docker compose build`.
