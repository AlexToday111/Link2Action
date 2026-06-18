alter table transcription_tasks
    add column if not exists title text;

alter table transcription_tasks
    add column if not exists duration_seconds bigint;

alter table transcription_tasks
    add column if not exists last_progress_status varchar(32);
