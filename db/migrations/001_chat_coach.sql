-- 001: chat-first coaching assistant
-- Idempotent: safe to run more than once.
--
-- * tasks.notes  → tasks.description (the living, LLM-maintained state of the task)
-- * tasks: subtasks (parent_id) and blocking (blocked_reason / blocked_until)
-- * task_events: append-only interaction log per task (postponed, committed, progress, …)
-- * messages: linked to tasks (task_id = main task, task_ids = every task a grouped message covers)
--   and new kinds: nudge (task check-in), meeting (meeting reminder), event ([Marked done: …])

-- ---------------------------------------------------------------- tasks

do $$
begin
    if exists (select 1 from information_schema.columns
               where table_schema = 'public' and table_name = 'tasks' and column_name = 'notes')
       and not exists (select 1 from information_schema.columns
               where table_schema = 'public' and table_name = 'tasks' and column_name = 'description') then
        alter table public.tasks rename column notes to description;
    end if;
end $$;

alter table tasks add column if not exists description    text;
alter table tasks add column if not exists parent_id      bigint references tasks (id) on delete cascade;
alter table tasks add column if not exists blocked_reason text;
alter table tasks add column if not exists blocked_until  timestamptz;

create index if not exists tasks_parent_idx on tasks (parent_id) where parent_id is not null;

-- ---------------------------------------------------------------- task_events

create table if not exists task_events (
    id         bigserial primary key,
    task_id    bigint      not null references tasks (id) on delete cascade,
    kind       text        not null check (kind in (
                   'postponed',            -- "later", snooze button, moved the time
                   'committed',            -- "I'll do it at 17:00"
                   'progress',             -- did part of it
                   'stuck',                -- doesn't know how to continue
                   'blocked',              -- waiting on something external
                   'unblocked',
                   'ignored',              -- a check-in got no reaction
                   'done',
                   'description_changed'   -- note = the previous description (so nothing is lost)
               )),
    note       text,
    created_at timestamptz not null default now()
);

create index if not exists task_events_task_idx on task_events (task_id, created_at desc);

alter table task_events enable row level security;

-- ---------------------------------------------------------------- messages

alter table messages add column if not exists task_id  bigint references tasks (id) on delete set null;
alter table messages add column if not exists task_ids bigint[] not null default '{}';

alter table messages drop constraint if exists messages_kind_check;
alter table messages add constraint messages_kind_check
    check (kind in ('chat', 'morning', 'checkin', 'evening', 'nudge', 'meeting', 'event'));

create index if not exists messages_task_ids_idx on messages using gin (task_ids);
