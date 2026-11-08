-- Annoying Reminder — Supabase schema
-- Run once in Supabase: SQL Editor → New query → paste → Run.
--
-- Security model: RLS is ON for every table and there are NO policies, so the
-- public anon key (used by the web app only for login) cannot read or write
-- anything. Only the backend touches data, through DATABASE_URL (postgres role,
-- which bypasses RLS). Never put DATABASE_URL or the service key in the web app.

create table if not exists tasks (
    id               bigserial primary key,
    title            text        not null,
    notes            text,
    kind             text        not null default 'task'   check (kind in ('task', 'meeting')),
    priority         smallint    not null default 1        check (priority between 1 and 3),
    status           text        not null default 'open'   check (status in ('open', 'done', 'cancelled')),
    due_at           timestamptz,           -- deadline, or meeting start time
    remind_at        timestamptz,           -- next time to notify (null = no pending notification)
    recurrence       text                   check (recurrence in ('daily', 'weekdays', 'weekly', 'monthly')),
    nag_count        integer     not null default 0,
    escalated        boolean     not null default false,  -- Pushover emergency already sent
    last_notified_at timestamptz,
    created_at       timestamptz not null default now(),
    completed_at     timestamptz
);

create index if not exists tasks_open_remind_idx on tasks (remind_at) where status = 'open';
create index if not exists tasks_open_due_idx    on tasks (due_at)    where status = 'open';

-- Chat history (also stores morning plans / check-ins so they show in the app)
create table if not exists messages (
    id         bigserial primary key,
    role       text        not null check (role in ('user', 'assistant')),
    content    text        not null,
    kind       text        not null default 'chat' check (kind in ('chat', 'morning', 'checkin', 'evening')),
    created_at timestamptz not null default now()
);

create index if not exists messages_created_idx on messages (created_at desc);

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

alter table tasks              enable row level security;
alter table messages           enable row level security;
alter table push_subscriptions enable row level security;
alter table job_runs           enable row level security;
