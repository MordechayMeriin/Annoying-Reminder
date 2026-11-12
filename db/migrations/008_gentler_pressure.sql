-- 008: gentler pressure (Phase 3 of the ADHD-assistant brief). Idempotent, additive.
--
-- parked_until, stale_offered_at, kind 'fence', task_events.avoid_reason and the preferences table
-- came with 006. Phase 3 needs three more facts per task, and one more event kind:
-- * tasks.avoid_asked_at  the "what's in the way?" message (Too big / Unclear / Boring / Not now) went out
-- * tasks.avoid_asks      how many times since the task's last fresh start (the second is the last;
--                         after it, silence parks the task)
-- * tasks.avoid_reset_at  the task's last fresh start (a park, a reason given, a session, progress):
--                         postponed / ignored events before it don't count toward "avoided"
-- * task_events.kind 'avoided'  the user said why they avoid the task (with avoid_reason)

alter table tasks add column if not exists avoid_asked_at timestamptz;
alter table tasks add column if not exists avoid_asks     smallint not null default 0;
alter table tasks add column if not exists avoid_reset_at timestamptz;

alter table task_events drop constraint if exists task_events_kind_check;
alter table task_events add constraint task_events_kind_check check (kind in (
    'postponed', 'committed', 'progress', 'stuck', 'blocked', 'unblocked', 'ignored', 'done',
    'description_changed', 'session_started', 'session_ended', 'paused', 'parked', 'avoided'
));
