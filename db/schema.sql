-- Annoying Reminder — Supabase schema (full, current state)
-- New project: SQL Editor → New query → paste → Run.
-- Existing project: run the files in db/migrations/ in order instead (they are idempotent).
--
-- Security model: RLS is ON for every table and there are NO policies, so the
-- public anon key (used by the web app only for login) cannot read or write
-- anything. Only the backend touches data, through DATABASE_URL (postgres role,
-- which bypasses RLS). Never put DATABASE_URL or the service key in the web app.

create table if not exists tasks (
    id               bigserial primary key,
    parent_id        bigint      references tasks (id) on delete cascade,  -- set for subtasks
    title            text        not null,
    description      text,                  -- living state of the task, rewritten by the assistant
    next_action      text,                  -- the first physical step (verb first, 15 min or less)
    estimate_min     integer                check (estimate_min is null or estimate_min between 1 and 10000),
    focus_date       date,                  -- the day this task is in Today's 3
    focus_rank       smallint,              -- its place in Today's 3 (1 = first)
    resume_note      text,                  -- "where did you stop?" (shown next time)
    parked_until     timestamptz,           -- "Not now": no check-ins before this time
    stale_offered_at timestamptz,           -- Sunday cleanup offered it (cooldown)
    avoid_asked_at   timestamptz,           -- the "what's in the way?" message went out
    avoid_asks       smallint    not null default 0,  -- ... this many times since the last fresh start
    avoid_reset_at   timestamptz,           -- fresh start: earlier postponed / ignored events don't count
    kind             text        not null default 'task'   check (kind in ('task', 'meeting', 'fence')),  -- fence: a fixed hard stop
    priority         smallint    not null default 1        check (priority between 1 and 3),
    status           text        not null default 'open'   check (status in ('open', 'done', 'cancelled')),
    due_at           timestamptz,           -- deadline, or meeting start time
    remind_at        timestamptz,           -- next time to contact the user about it (null = none pending)
    remind_requested boolean     not null default false,  -- remind_at was chosen by the user
    recurrence       text                   check (recurrence in ('daily', 'weekdays', 'weekly', 'monthly')),
    nag_count        integer     not null default 0,
    escalated        boolean     not null default false,  -- Pushover emergency already sent
    blocked_reason   text,
    blocked_until    timestamptz,           -- no check-ins about this task before this time
    last_notified_at timestamptz,
    created_at       timestamptz not null default now(),
    completed_at     timestamptz
);

create index if not exists tasks_open_remind_idx on tasks (remind_at) where status = 'open';
create index if not exists tasks_open_due_idx    on tasks (due_at)    where status = 'open';
create index if not exists tasks_parent_idx      on tasks (parent_id) where parent_id is not null;
create index if not exists tasks_focus_idx       on tasks (focus_date) where focus_date is not null;

-- Append-only interaction log per task: what the user said / did about it over time
create table if not exists task_events (
    id         bigserial primary key,
    task_id    bigint      not null references tasks (id) on delete cascade,
    kind       text        not null check (kind in (
                   'postponed', 'committed', 'progress', 'stuck', 'blocked', 'unblocked',
                   'ignored', 'done', 'description_changed',
                   'session_started', 'session_ended', 'paused', 'parked',
                   'avoided'              -- the user said why they avoid it (see avoid_reason)
               )),
    note       text,
    avoid_reason text  check (avoid_reason in ('too_big', 'unclear', 'boring', 'scary', 'people', 'no_energy')),
    created_at timestamptz not null default now()
);

create index if not exists task_events_task_idx on task_events (task_id, created_at desc);

-- The single chat thread. Proactive contacts (check-ins, meeting reminders, daily plans) are
-- saved here first and then pushed. The LLM only sees the current day (see DAY_RESET_TIME).
create table if not exists messages (
    id         bigserial primary key,
    role       text        not null check (role in ('user', 'assistant')),
    content    text        not null,
    kind       text        not null default 'chat' check (kind in (
                   'chat', 'morning', 'checkin', 'evening',  -- conversation and daily jobs
                   'nudge',                                  -- assistant's own task check-in
                   'reminder',                               -- a reminder the user asked for
                   'meeting',                                -- meeting reminder
                   'event',                                  -- "[Marked done: …]" (role = user)
                   'session',                                -- focus session start / end
                   'reentry'                                 -- the first message after a lapse
               )),
    task_id    bigint      references tasks (id) on delete set null,  -- main task it's about
    task_ids   bigint[]    not null default '{}',                     -- every task it's about
    chips      jsonb       not null default '[]',                     -- reply buttons, chosen by code
    created_at timestamptz not null default now()
);

create index if not exists messages_created_idx  on messages (created_at desc);
create index if not exists messages_task_ids_idx on messages using gin (task_ids);

-- Browser push subscriptions (one per device/browser)
create table if not exists push_subscriptions (
    id         bigserial primary key,
    endpoint   text        not null unique,
    p256dh     text        not null,
    auth       text        not null,
    created_at timestamptz not null default now()
);

-- Makes sure each daily job (morning plan, check-in, evening review) runs once per day
create table if not exists job_runs (
    job      text not null,
    run_date date not null,
    ran_at   timestamptz not null default now(),
    primary key (job, run_date)
);

-- Behaviour settings edited in the app (⚙ Settings). No row = default (env var, else built-in).
create table if not exists app_settings (
    key        text        primary key,
    value      text        not null,
    updated_at timestamptz not null default now()
);

-- Focus sessions: ▶ Start → a timer → one check-in at the end
create table if not exists sessions (
    id          bigserial primary key,
    task_id     bigint      not null references tasks (id) on delete cascade,
    started_at  timestamptz not null default now(),
    planned_min integer     not null check (planned_min > 0 and planned_min % 5 = 0),
    ended_at    timestamptz,
    outcome     text        check (outcome in ('done', 'another', 'stopped', 'paused')),
    end_sent_at    timestamptz,  -- the end check-in went out (silence → paused counts from it)
    note_closed_at timestamptz,  -- "Where did you stop?" answered or dismissed
    user_id     uuid        -- unused: keeps a later multi-user step cheap
);
create index if not exists sessions_open_idx    on sessions (started_at) where ended_at is null;
create index if not exists sessions_task_idx    on sessions (task_id, started_at desc);
create index if not exists sessions_started_idx on sessions (started_at desc);

-- Preferences stated in chat ("shorter messages please"), injected into every prompt
create table if not exists preferences (
    id         bigserial primary key,
    text       text        not null,
    created_at timestamptz not null default now(),
    user_id    uuid        -- unused: keeps a later multi-user step cheap
);

alter table tasks              enable row level security;
alter table task_events        enable row level security;
alter table messages           enable row level security;
alter table push_subscriptions enable row level security;
alter table job_runs           enable row level security;
alter table app_settings       enable row level security;
alter table sessions           enable row level security;
alter table preferences        enable row level security;

-- Hardening (same as migrations/002): RLS doesn't cover TRUNCATE, so drop the API roles' grants.
revoke all on table tasks, task_events, messages, push_subscriptions, job_runs, app_settings,
    sessions, preferences from anon, authenticated;
revoke all on all sequences in schema public from anon, authenticated;
