# 🔔 Annoying Reminder

A personal, LLM-powered reminder that doesn't let things slide. You log tasks and meetings in plain
Hebrew or English, it reminds you on time, sends a morning plan, checks in during the day, and keeps
nagging until things are done or snoozed.

## How it works

```
 Phone (Chrome web app)                Render (free)                         Supabase (free)
 ┌──────────────────────┐   HTTPS   ┌──────────────────────────┐   SQL    ┌──────────────┐
 │ chat · tasks · plan  │ ────────▶ │ FastAPI                  │ ───────▶ │ Postgres     │
 │ push notifications   │ ◀──push── │  • agent (OpenRouter)    │          │ (RLS on)     │
 │  [✓ Done] [⏰ Snooze] │           │  • /tick reminders/jobs  │          └──────────────┘
 └──────────────────────┘           └──────────────────────────┘
                                        ▲ every 5 min          └──▶ Pushover (critical backup)
                                  cron-job.org (X-Cron-Secret)
```

- **Reminders** are plain SQL, so every 5-minute tick costs nothing. The LLM is only used when you
  chat and for the daily morning plan, check-ins and evening review.
- **Nagging:** an unacknowledged reminder repeats every `NAG_INTERVAL_MIN` (faster for higher
  priority) up to `MAX_NAGS` times. Critical (priority 3) tasks then escalate to a Pushover
  emergency alert that repeats until you acknowledge it.
- **Meetings** get a reminder `MEETING_LEAD_MIN` before and one at start time.
- **Quiet hours** hold non-critical reminders until morning.
- **Recurring tasks** (daily / weekdays Sun–Thu / weekly / monthly) roll forward when marked done.

## Security: only you can use it

| Layer | Protection |
|---|---|
| OpenRouter key | Server-side only (Render env). Dedicated key with a monthly credit limit. |
| Login | Supabase Auth, sign-ups disabled, `shouldCreateUser: false`. |
| API | Every request verified with Supabase **and** checked against `OWNER_USER_ID` → others get 403. |
| `/tick` | Requires the `X-Cron-Secret` header. |
| Notification buttons | HMAC-signed, expiring, per-task tokens. |
| Database | RLS on, no policies → the public anon key can't read anything. |
| Spend | Chat rate limit + daily LLM-call cap (`CHAT_PER_MINUTE`, `LLM_CALLS_PER_DAY`). |

## Setup checklist

### 1. Supabase
1. Create a project (free plan).
2. **SQL Editor** → paste [`db/schema.sql`](db/schema.sql) → Run.
3. **Authentication → Sign In / Providers**: keep Email enabled, **turn off "Allow new users to sign up"**.
4. **Authentication → Users → Add user → Create new user** with your email (tick auto-confirm). Copy its **User UID** → `OWNER_USER_ID`.
5. **Authentication → Emails → Magic Link** template: add `Your code: {{ .Token }}` so you can log in with a code inside the installed app.
6. **Authentication → URL Configuration**: Site URL = your web app URL (step 3), and add it to Redirect URLs.
7. **Project Settings → API**: copy Project URL → `SUPABASE_URL`, anon/publishable key → `SUPABASE_ANON_KEY`.
8. **Connect → Connection string → Session pooler**: copy → `DATABASE_URL` (replace the password).

### 2. Secrets
```bash
pip install cryptography
python backend/scripts/gen_secrets.py
```
Gives `VAPID_PRIVATE_KEY`, `VAPID_PUBLIC_KEY`, `CRON_SECRET`, `ACTION_SECRET`.

OpenRouter: **Keys → Create key** just for this app, set a credit limit (e.g. $3/month) → `OPENROUTER_API_KEY`.
Pick models at <https://openrouter.ai/models> (must support tool calling) → `OPENROUTER_MODELS`,
e.g. a cheap paid model first, then a `:free` fallback.

### 3. Render
1. **New → Blueprint** → select this repo. It creates `annoying-reminder-api` and `annoying-reminder-web`.
2. Fill in the env vars it asks for. `WEB_ORIGIN` / `API_URL` are the two `.onrender.com` URLs
   (if you don't know them yet, put placeholders, then fix them after the first deploy).
   `VAPID_SUBJECT` = `mailto:your-email`.
3. Edit [`web/config.js`](web/config.js) with `SUPABASE_URL`, `SUPABASE_ANON_KEY` and the API URL, commit, push.
4. Check `https://<api>.onrender.com/health` returns `{"ok":true}`.

### 4. cron-job.org
1. Sign up (free) → **Create cronjob**.
2. URL: `https://<api>.onrender.com/tick`, schedule **every 5 minutes**.
3. **Advanced**: Request method **POST**, add header `X-Cron-Secret: <your CRON_SECRET>`.
4. Turn on failure notifications. The ping also keeps the free Render service from sleeping.

### 5. Phone
1. Open the web app URL in Chrome → log in with your email (code or link).
2. Tap **Enable notifications** → allow. A test notification should arrive.
3. Chrome menu ⋮ → **Add to Home screen / Install app** (from Chrome itself, not an APK).
4. Recommended: Android Settings → Apps → Chrome → Battery → **Unrestricted**, so pushes aren't delayed.

## Using it

Type in the chat box, for example:
- `Meeting with Dani tomorrow 14:00`
- `לשלם ארנונה עד יום ראשון, חשוב`
- `Call mom every Friday 12:00`
- `Done with the stats assignment`
- `What's left for today?`

## Local development

```bash
cd backend
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt pytest
cp .env.example .env   # fill in, then export the variables
python -m pytest -q
uvicorn app.main:app --reload
```

## Project layout

```
backend/app/
  main.py       API routes
  agent.py      chat agent + tool definitions (add/list/update/complete/snooze/cancel)
  scheduler.py  /tick: due reminders, nagging, morning/check-in/evening jobs
  tasks.py      task storage and operations
  logic.py      pure scheduling logic (tested)
  llm.py        OpenRouter client with fallback + daily cap
  notify.py     web push + Pushover
  auth.py       owner-only login check, cron secret
  tokens.py     signed notification-button tokens
db/schema.sql   Supabase schema
web/            static web app (installable), service worker for push
render.yaml     Render Blueprint
```
