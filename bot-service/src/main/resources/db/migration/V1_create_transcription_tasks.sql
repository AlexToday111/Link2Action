create table transcription_tasks (
     id uuid primary key,

     telegram_chat_id bigint not null,
     telegram_user_id bigint not null,

     source_url text not null,
     status varchar(32) not null,

     requested_format varchar(32) not null,
     language varchar(16),

     result_txt_path text,
     result_md_path text,

     error_message text,

     created_at timestamp with time zone not null,
     updated_at timestamp with time zone not null,
     started_at timestamp with time zone,
     finished_at timestamp with time zone
);

create index idx_transcription_tasks_user_created_at
    on transcription_tasks (telegram_user_id, created_at desc);

create index idx_transcription_tasks_status
    on transcription_tasks (status);

create index idx_transcription_tasks_user_status
    on transcription_tasks (telegram_user_id, status);