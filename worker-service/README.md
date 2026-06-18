# worker-service

`worker-service` consumes transcription requests from RabbitMQ, downloads audio for the requested video, transcribes it with Whisper, writes result files to the shared data volume, and publishes completion or failure events back to RabbitMQ.

The service does not call Telegram. Telegram delivery is handled by `bot-service` after it receives result events.

## RabbitMQ contract

The worker uses the same exchange, queues, and routing keys as `bot-service`:

- Exchange: `linkscribe.exchange`
- Request queue: `linkscribe.transcription.requests`
- Result queue: `linkscribe.transcription.results`
- Request routing key: `transcription.requested`
- Completed routing key: `transcription.completed`
- Failed routing key: `transcription.failed`
- Progress routing key: `transcription.progress`

Requests are consumed from the request queue. Progress, completion, and failure events are published to the configured exchange with the matching routing key.

## Environment

| Variable | Default |
| --- | --- |
| `RABBITMQ_HOST` | `rabbitmq` |
| `RABBITMQ_PORT` | `5672` |
| `RABBITMQ_USERNAME` | `guest` |
| `RABBITMQ_PASSWORD` | `guest` |
| `RABBITMQ_EXCHANGE` | `linkscribe.exchange` |
| `RABBITMQ_REQUEST_QUEUE` | `linkscribe.transcription.requests` |
| `RABBITMQ_RESULT_QUEUE` | `linkscribe.transcription.results` |
| `RABBITMQ_REQUEST_ROUTING_KEY` | `transcription.requested` |
| `RABBITMQ_COMPLETED_ROUTING_KEY` | `transcription.completed` |
| `RABBITMQ_FAILED_ROUTING_KEY` | `transcription.failed` |
| `RABBITMQ_PROGRESS_ROUTING_KEY` | `transcription.progress` |
| `RABBITMQ_HEARTBEAT` | `0` |
| `RABBITMQ_BLOCKED_CONNECTION_TIMEOUT` | `300` |
| `RESULTS_BASE_PATH` | `/data/results` |
| `DOWNLOADS_BASE_PATH` | `/data/downloads` |
| `WHISPER_MODEL` | `small` |
| `WHISPER_DEVICE` | `cpu` |
| `WHISPER_COMPUTE_TYPE` | `int8` |
| `WHISPER_LANGUAGE` | empty, auto-detect |
| `MAX_VIDEO_DURATION_SECONDS` | `3600` |
| `RABBITMQ_PREFETCH_COUNT` | `1` |
| `RABBITMQ_CONNECTION_ATTEMPTS` | `30` |
| `RABBITMQ_CONNECTION_RETRY_SECONDS` | `2` |

## Local run

```bash
cd worker-service
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
python -m app.main
```

For local runs outside Docker, set `RABBITMQ_HOST=localhost` if RabbitMQ is exposed on the host.

## Docker run

```bash
docker build -t link2action-worker-service .
docker run --rm \
  --env-file .env.example \
  -v "$(pwd)/../data:/data" \
  link2action-worker-service
```

The final files are written to:

- `/data/results/{taskId}/transcript.txt`
- `/data/results/{taskId}/transcript.md`

Temporary downloads are stored under `/data/downloads/{taskId}` and removed after processing.
