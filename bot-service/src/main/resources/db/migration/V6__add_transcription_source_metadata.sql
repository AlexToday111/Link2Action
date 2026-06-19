alter table transcription_tasks
    add column if not exists source_type varchar(32) not null default 'URL';

alter table transcription_tasks
    add column if not exists telegram_file_id text;

alter table transcription_tasks
    add column if not exists telegram_file_unique_id text;

alter table transcription_tasks
    add column if not exists original_file_name text;

alter table transcription_tasks
    add column if not exists mime_type text;

alter table transcription_tasks
    add column if not exists file_size_bytes bigint;

alter table transcription_tasks
    alter column source_url drop not null;

update transcription_tasks
set source_type = 'URL'
where source_type is null;

update transcription_tasks
set idempotency_key = md5(
    telegram_user_id::text
    || '|'
    || source_type
    || '|'
    || coalesce(nullif(btrim(source_url), ''), '')
    || '|'
    || coalesce(nullif(btrim(telegram_file_unique_id), ''), nullif(btrim(telegram_file_id), ''), '')
    || '|'
    || upper(btrim(requested_format))
    || '|'
    || lower(btrim(coalesce(language, '')))
)
where idempotency_key is not null;
