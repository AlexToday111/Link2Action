# Link2Action

Link2Action — Telegram-бот для расшифровки видео по ссылке.
Пользователь отправляет ссылку на видео, выбирает формат результата, а бот возвращает транскрипт в TXT и/или Markdown.

Проект состоит из двух сервисов:

* `bot-service` — Kotlin/Spring Boot сервис для работы с Telegram, PostgreSQL и RabbitMQ.
* `worker-service` — Python-сервис для скачивания аудио и расшифровки через Whisper.

## Запуск

Создайте `.env` в корне проекта:

```env
TELEGRAM_BOT_TOKEN=your_telegram_bot_token
TELEGRAM_BOT_USERNAME=your_bot_username
```

Запустите проект:

```bash
docker compose up --build
```

После запуска откройте @Link2ActionBot и отправьте команду:

```text
/start
```

Для остановки:

```bash
docker compose down
```

Для полного сброса локальных данных:

```bash
docker compose down -v
rm -rf data/*
```
