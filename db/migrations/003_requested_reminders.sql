-- 003: tell reminders the user asked for apart from the assistant's own check-ins.
-- Idempotent, additive.
--
-- * tasks.remind_requested: true when the current remind_at was chosen by the user
--   (a due/remind time they gave, ⏰ snooze, "remind me at 17:00"). Such reminders don't count
--   toward the per-task daily cap.
-- * messages.kind 'reminder': a sent reminder the user asked for. 'nudge' stays the assistant's
--   own check-in, and only nudges count toward the per-task cap.

alter table tasks add column if not exists remind_requested boolean not null default false;

-- Existing open tasks that haven't been nagged yet are waiting on a time the user gave.
update tasks set remind_requested = true
 where status = 'open' and remind_at is not null and nag_count = 0 and remind_requested = false;

alter table messages drop constraint if exists messages_kind_check;
alter table messages add constraint messages_kind_check
    check (kind in ('chat', 'morning', 'checkin', 'evening', 'nudge', 'reminder', 'meeting', 'event'));
