-- 006: ADHD assistant (Phases 1–3 of the implementation brief). Idempotent, additive.
--
-- Phase 1 uses: tasks.next_action / estimate_min / focus_date, messages.chips.
-- The rest is created now so later phases don't need another migration, and the code running before
-- this deploy keeps working against it (every change is additive).
--
-- * tasks.next_action      the first physical step (verb first, 15 minutes or less), filled in silently
-- * tasks.estimate_min     the assistant's guess of the whole task's length
-- * tasks.focus_date       the day this task is in Today's 3; focus_rank its place in it (an addition
--                          to the brief: "Start the first one" and the focus card need the order)
-- * tasks.resume_note      "where did you stop?" (Phase 2)
-- * tasks.parked_until     "Not now" parks a task (Phase 3)
-- * tasks.stale_offered_at Sunday cleanup cooldown (Phase 3)
-- * tasks.kind 'fence'     a fixed hard stop learned from chat (Phase 3)
-- * task_events            avoid_reason; kinds session_started / session_ended / paused / parked
-- * messages.chips         the reply buttons under a message, chosen by code
-- * messages.kind          'session' (session start/end), 'reentry' (after a lapse)
-- * sessions, preferences  Phase 2 / Phase 3 tables

-- ---------------------------------------------------------------- tasks

alter table tasks add column if not exists next_action      text;
alter table tasks add column if not exists estimate_min     integer;
alter table tasks add column if not exists focus_date       date;
alter table tasks add column if not exists focus_rank       smallint;     -- order within Today's 3 (1 = first)
alter table tasks add column if not exists resume_note      text;
alter table tasks add column if not exists parked_until     timestamptz;
alter table tasks add column if not exists stale_offered_at timestamptz;

alter table tasks drop constraint if exists tasks_kind_check;
alter table tasks add constraint tasks_kind_check check (kind in ('task', 'meeting', 'fence'));

alter table tasks drop constraint if exists tasks_estimate_min_check;
alter table tasks add constraint tasks_estimate_min_check check (estimate_min is null or estimate_min between 1 and 10000);

create index if not exists tasks_focus_idx on tasks (focus_date) where focus_date is not null;

-- ---------------------------------------------------------------- task_events

alter table task_events add column if not exists avoid_reason text;

alter table task_events drop constraint if exists task_events_avoid_reason_check;
alter table task_events add constraint task_events_avoid_reason_check
    check (avoid_reason in ('too_big', 'unclear', 'boring', 'scary', 'people', 'no_energy'));

alter table task_events drop constraint if exists task_events_kind_check;
alter table task_events add constraint task_events_kind_check check (kind in (
    'postponed', 'committed', 'progress', 'stuck', 'blocked', 'unblocked', 'ignored', 'done',
    'description_changed', 'session_started', 'session_ended', 'paused', 'parked'
));

-- ---------------------------------------------------------------- messages

alter table messages add column if not exists chips jsonb not null default '[]';

alter table messages drop constraint if exists messages_kind_check;
alter table messages add constraint messages_kind_check check (kind in (
    'chat', 'morning', 'checkin', 'evening', 'nudge', 'reminder', 'meeting', 'event', 'session', 'reentry'
));

-- ---------------------------------------------------------------- sessions (Phase 2)

create table if not exists sessions (
    id          bigserial primary key,
    task_id     bigint      not null references tasks (id) on delete cascade,
    started_at  timestamptz not null default now(),
    planned_min integer     not null check (planned_min > 0 and planned_min % 5 = 0),
    ended_at    timestamptz,
    outcome     text        check (outcome in ('done', 'another', 'stopped', 'paused')),
    user_id     uuid        -- unused: keeps a later multi-user step cheap
);
create index if not exists sessions_open_idx on sessions (started_at) where ended_at is null;

-- ---------------------------------------------------------------- preferences (Phase 3)

create table if not exists preferences (
    id         bigserial primary key,
    text       text        not null,
    created_at timestamptz not null default now(),
    user_id    uuid        -- unused: keeps a later multi-user step cheap
);

alter table sessions    enable row level security;
alter table preferences enable row level security;
revoke all on table sessions, preferences from anon, authenticated;
revoke all on all sequences in schema public from anon, authenticated;
