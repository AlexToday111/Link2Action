alter table transcription_tasks
    add column if not exists deleted_at timestamp with time zone;

alter table transcription_tasks
    add column if not exists progress_message_id bigint;
