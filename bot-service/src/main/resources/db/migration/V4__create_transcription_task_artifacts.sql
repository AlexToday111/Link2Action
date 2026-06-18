create table if not exists transcription_task_artifacts (
    id uuid primary key,
    task_id uuid not null references transcription_tasks(id) on delete cascade,
    telegram_chat_id bigint not null,
    telegram_message_id bigint not null,
    artifact_type varchar(32) not null,
    file_path text,
    created_at timestamp with time zone not null
);

create index if not exists idx_task_artifacts_task_id
    on transcription_task_artifacts(task_id);

create index if not exists idx_task_artifacts_chat_message
    on transcription_task_artifacts(telegram_chat_id, telegram_message_id);
