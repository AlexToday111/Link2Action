alter table transcription_tasks
    add column if not exists idempotency_key varchar(128);

update transcription_tasks
set idempotency_key = md5(
    telegram_user_id::text
    || '|'
    || btrim(source_url)
    || '|'
    || upper(btrim(requested_format))
    || '|'
    || lower(btrim(coalesce(language, '')))
)
where idempotency_key is null;

with duplicate_active_tasks as (
    select id
    from (
        select
            id,
            row_number() over (
                partition by idempotency_key
                order by created_at desc, id
            ) as duplicate_rank
        from transcription_tasks
        where idempotency_key is not null
          and deleted_at is null
          and status in ('QUEUED', 'PROCESSING')
    ) ranked
    where duplicate_rank > 1
)
update transcription_tasks
set idempotency_key = null
where id in (select id from duplicate_active_tasks);

create unique index if not exists uq_transcription_tasks_active_idempotency_key
    on transcription_tasks (idempotency_key)
    where idempotency_key is not null
      and deleted_at is null
      and status in ('QUEUED', 'PROCESSING');
