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

Telegram-бот для расшифровки видео по ссылке или загруженного Telegram media. Пользователь отправляет ссылку, видео, аудио или voice message, выбирает цель обработки и формат результата, а бот возвращает транскрипт, LLM-ready Markdown, prompt или LLM Package.

Проект состоит из двух сервисов:

* `bot-service` — Kotlin/Spring Boot сервис для работы с Telegram, PostgreSQL и RabbitMQ.
* `worker-service` — Python-сервис для скачивания аудио и расшифровки через Whisper.

---

## Processing modes

Доступные режимы:

* `TRANSCRIPT` — полный транскрипт в TXT и/или Markdown.
* `SUMMARY` — LLM-ready Markdown и prompt для summary.
* `ACTION_ITEMS` — transcript и prompt для извлечения задач в LLM.
* `STUDY_NOTES` — prompt для учебного конспекта.
* `TECH_TASKS` — prompt для технических задач разработки.
* `CONTENT_REPURPOSE` — prompt для постов, статьи, hooks и title ideas.

Режимы кроме `TRANSCRIPT` не вызывают реальные LLM API. Worker готовит транскрипт, Markdown-шаблон и prompt для дальнейшей обработки пользователем.

## LLM Launcher

После завершения задачи бот может показать кнопки открытия:

* ChatGPT
* Claude
* Gemini
* Perplexity

Бот не отправляет пользовательские данные в эти сервисы сам. Кнопка только открывает сайт выбранной LLM, а пользователь самостоятельно копирует `llm_prompt.txt` или загружает LLM Package.

Переменные окружения:

```env
LLM_LAUNCHER_ENABLED=true
LLM_CHATGPT_URL=https://chatgpt.com/
LLM_CLAUDE_URL=https://claude.ai/
LLM_GEMINI_URL=https://gemini.google.com/
LLM_PERPLEXITY_URL=https://www.perplexity.ai/
```

## LLM Package

`llm_package.zip` содержит:

```text
transcript.md
transcript.txt
llm_prompt.txt
README.md
metadata.json
```

Пакет предназначен для дальнейшей ручной работы в выбранной LLM: открыть сервис, загрузить `transcript.md` или вставить `llm_prompt.txt`.

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
