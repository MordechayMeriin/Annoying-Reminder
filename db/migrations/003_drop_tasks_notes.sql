-- 003: drop tasks.notes (replaced by tasks.description in 001).
-- Run ONLY after the chat-coach backend is deployed: older code still selects `notes`.
-- Idempotent.

do $$
begin
    if exists (select 1 from information_schema.columns
               where table_schema = 'public' and table_name = 'tasks' and column_name = 'notes') then
        -- copy anything the old code wrote between 001 and the deploy
        update public.tasks set description = notes where description is null and notes is not null;
        alter table public.tasks drop column notes;
    end if;
end $$;
