alter table transcription_tasks
    add column if not exists processing_mode varchar(32) not null default 'TRANSCRIPT';

alter table transcription_tasks
    add column if not exists result_prompt_path text;

alter table transcription_tasks
    add column if not exists result_package_path text;

update transcription_tasks
set processing_mode = 'TRANSCRIPT'
where processing_mode is null;
