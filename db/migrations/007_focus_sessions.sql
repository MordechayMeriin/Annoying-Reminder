-- 007: focus sessions and breadcrumbs (Phase 2 of the ADHD-assistant brief). Idempotent, additive.
--
-- The sessions table itself came with 006. Phase 2 needs two more facts per session:
-- * end_sent_at     the end check-in went out (claimed before sending, so overlapping ticks can't send
--                   it twice; silence is counted from it)
-- * note_closed_at  "Where did you stop?" was answered or dismissed (the field is optional and goes
--                   away by itself after a few hours)

alter table sessions add column if not exists end_sent_at    timestamptz;
alter table sessions add column if not exists note_closed_at timestamptz;

create index if not exists sessions_task_idx    on sessions (task_id, started_at desc);
create index if not exists sessions_started_idx on sessions (started_at desc);
