<div align="center">

<img src="docs/assets/link2action-banner.png" alt="Link2Action banner" width="100%" />

# Link2Action

**Telegram-бот для превращения видео-ссылок в транскрипты, Markdown и action-ready материалы.**

![Kotlin](https://img.shields.io/badge/Kotlin-2.2.21-7F52FF?style=for-the-badge\&logo=kotlin\&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.8-6DB33F?style=for-the-badge\&logo=springboot\&logoColor=white)
![Python](https://img.shields.io/badge/Python-3.11-3776AB?style=for-the-badge\&logo=python\&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?style=for-the-badge\&logo=postgresql\&logoColor=white)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-3-FF6600?style=for-the-badge\&logo=rabbitmq\&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=for-the-badge\&logo=docker\&logoColor=white)
![Telegram](https://img.shields.io/badge/Telegram-Bot-26A5E4?style=for-the-badge\&logo=telegram\&logoColor=white)
![Whisper](https://img.shields.io/badge/Whisper-Transcription-111827?style=for-the-badge)

</div>

---

<div align="center">

## О проекте

</div>

Telegram-бот для расшифровки видео по ссылке. Пользователь отправляет ссылку на видео, выбирает формат результата, а бот возвращает транскрипт в **TXT** и/или **Markdown**.

Проект состоит из двух сервисов:

* `bot-service` — Kotlin/Spring Boot сервис для работы с Telegram, PostgreSQL и RabbitMQ.
* `worker-service` — Python-сервис для скачивания аудио и расшифровки через Whisper.

---

<div align="center">

## Запуск

</div>

Создайте `.env` в корне проекта:

```env
TELEGRAM_BOT_TOKEN=your_telegram_bot_token
TELEGRAM_BOT_USERNAME=your_bot_username
```

Запустите проект:

```bash
docker compose up --build
```

После запуска откройте `@Link2ActionBot` и отправьте команду:

```text
/start
```

---

<div align="center">

## Остановка

</div>

Для остановки:

```bash
docker compose down
```

Для полного сброса локальных данных:

```bash
docker compose down -v
rm -rf data/*
```
