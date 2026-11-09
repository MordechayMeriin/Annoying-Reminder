-- 005: behaviour settings edited in the app (⚙ Settings). Idempotent, additive.
-- Only keys the backend knows (config.SPECS) are written; values are validated, canonical text.
-- A missing row means "use the default" (env var on Render, else the built-in default).

create table if not exists app_settings (
    key        text        primary key,
    value      text        not null,
    updated_at timestamptz not null default now()
);

alter table app_settings enable row level security;
revoke all on table app_settings from anon, authenticated;
