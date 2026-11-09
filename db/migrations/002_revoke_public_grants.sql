-- 002 (optional hardening): remove Supabase's default table grants from the API roles.
--
-- RLS with no policies already blocks select/insert/update/delete for anon and authenticated,
-- but RLS does not cover TRUNCATE. The backend connects as `postgres` (DATABASE_URL), which is
-- unaffected. The web app only uses the anon key for Supabase Auth, which doesn't need these.
-- Idempotent.

revoke all on table tasks, task_events, messages, push_subscriptions, job_runs from anon, authenticated;
revoke all on all sequences in schema public from anon, authenticated;

-- Tables created later in this schema don't get the grants either.
alter default privileges in schema public revoke all on tables    from anon, authenticated;
alter default privileges in schema public revoke all on sequences from anon, authenticated;
