# 🔔 Annoying Reminder

A personal, LLM-powered assistant that doesn't let things slide. It's one ongoing chat: you log tasks
and meetings in plain Hebrew or English, and it checks in on you, asks how it's going, suggests a tiny
first step when you're stuck, remembers progress and blockers, and decides sensibly when to follow up.

## How it works

```
 Phone (Chrome web app)                Render (free)                         Supabase (free)
 ┌──────────────────────┐   HTTPS   ┌──────────────────────────┐   SQL    ┌──────────────┐
 │ chat (main screen)   │ ────────▶ │ FastAPI                  │ ───────▶ │ Postgres     │
 │ tasks drawer · plan  │ ◀──push── │  • agent  (OpenRouter)   │          │ (RLS on)     │
 │  [✓ Done] [⏰ Snooze] │           │  • /tick → coach         │          └──────────────┘
 └──────────────────────┘           └──────────────────────────┘
                                        ▲ every 5 min          └──▶ Pushover (critical backup)
                                  cron-job.org (X-Cron-Secret)
```

**Code decides WHEN, the LLM decides WHAT.** Timing, limits and safety are deterministic code; the LLM
writes the messages and can adjust follow-ups through tools, within those limits.

### The chat is the product
- Every proactive contact (task check-in, meeting reminder, morning plan, mid-day check-in, evening
  review) is first saved as an assistant message in the chat, then pushed. The notification body is
  that message; tapping it opens the chat at that message with the reply box focused.
- ✓ Done / ⏰ Snooze buttons appear when a message is about exactly one task. Done / Snooze from a
  notification or the task list also writes a short event into the chat (`[Marked done: …]`).
- **Daily reset:** the assistant only sees today's chat (from `DAY_RESET_TIME`, default 04:00). Long-term
  memory lives on the tasks: each task's **description** is its current state (rewritten by the
  assistant whenever you tell it something new, editable in the task drawer), and its **history**
  (`task_events`) records postponed / committed / progress / stuck / blocked / ignored / done.

### Each tick (every 5 minutes)
1. Tasks whose `remind_at` has passed are due for contact.
2. Guardrails, in code, before any LLM call ([`logic.contact_decision`](backend/app/logic.py)):
   - quiet hours are never overruled, whatever the priority
   - `MIN_GAP_MIN` between proactive messages (meetings exempt)
   - `MAX_PROACTIVE_PER_DAY` messages per day (meetings skip it but count toward it)
   - `MAX_PER_TASK_PER_DAY` assistant check-ins per task (reminders you asked for don't count)
   - blocked tasks wait until their follow-up time
   - when several rules apply, the latest postponement wins
3. Everything due in the same tick becomes **one** message and **one** push.
4. The coach writes it with the LLM from a compact context (description, steps, history, linked
   messages, today's chat). It may call `schedule_followup`; otherwise the next check-in follows the
   priority-based nag interval. Meeting times always come from code.
5. **Fallback:** if the LLM errors, times out (`LLM_TIMEOUT_S`), returns nothing, hits a 429 or the
   daily cap, the old template text is sent instead. A reminder is never lost because of the LLM.
6. Ignored critical (priority 3) tasks still escalate to a Pushover emergency alert after `MAX_NAGS`.

The `/tick` request itself only decides and returns; the LLM work runs in a background task.

### Replies
When you reply, the agent gets the last check-in (if recent) and up to 3 candidate tasks with their
context, marked as a *guess*. The model decides what your message is about and may ask one short
question if it's ambiguous. The turn is linked to the tasks the model actually acted on.

### Tools (9, flat parameters for a mid-size model)
`add_task`, `list_tasks`, `update_task` (also description / unblock), `complete_task`, `cancel_task`,
`schedule_followup`, `add_subtask`, `log_interaction`, `mark_blocked`.

### Prompts are files
[`backend/app/prompts/`](backend/app/prompts/): `system.md` (chat), `checkin.md`, `daily_*.md`, and
`coach.md` — the "how to interact with me" skill that every other prompt includes via `{coach}`.
Edit or replace `coach.md` to change the assistant's style everywhere, no code changes.

## Security: only you can use it

| Layer | Protection |
|---|---|
| OpenRouter key | Server-side only (Render env). Dedicated key with a monthly credit limit. |
| Login | Supabase Auth, sign-ups disabled, `shouldCreateUser: false`. |
| API | Every request verified with Supabase **and** checked against `OWNER_USER_ID` → others get 403. |
| `/tick` | Requires the `X-Cron-Secret` header. |
| Notification buttons | HMAC-signed, expiring, per-task tokens. |
| Database | RLS on, no policies → the public anon key can't read anything. |
| Database grants | The API roles (`anon`, `authenticated`) have no table grants (RLS doesn't cover `TRUNCATE`). |
| Spend | Chat rate limit + daily LLM-call cap (`CHAT_PER_MINUTE`, `LLM_CALLS_PER_DAY`). |

## Setup checklist

### 1. Supabase
1. Create a project (free plan).
2. **SQL Editor** → paste [`db/schema.sql`](db/schema.sql) → Run. (Existing project: run the files in
   [`db/migrations/`](db/migrations/) in order instead; they are idempotent.)
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
e.g. `google/gemma-4-31b-it:free,openrouter/free`. The free tier allows 20 requests/min and 1,000/day;
with the default caps the coach uses at most a few dozen calls a day.

### 3. Render
1. **New → Blueprint** → select this repo. It creates `annoying-reminder-api` and `annoying-reminder-web`.
2. Fill in the env vars it asks for. `WEB_ORIGIN` / `API_URL` are the two `.onrender.com` URLs
   (if you don't know them yet, put placeholders, then fix them after the first deploy).
   `VAPID_SUBJECT` = `mailto:your-email`.
3. The **web** service also asks for `SUPABASE_URL`, `SUPABASE_ANON_KEY` and `API_URL`. Its build
   ([`web/build-config.sh`](web/build-config.sh)) writes them into `config.js`, so nothing
   project-specific is committed. These values are public by design; secrets go only on the API service.
   After changing them, use **Manual Deploy** on the web service to rebuild.
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
- `I'm stuck on the tax form` → it asks one question or suggests a 10–20 minute first step, and can
  break the task into steps
- `Later, I'll do it at 5` → it remembers the commitment and checks in at 17:00
- `Waiting for the landlord to answer` → marked blocked, it asks again later
- `What's left for today?`

The ☑ Tasks drawer lists today's and all open tasks (✓ / ⏰ on each row). Tap a task to see and edit
its description, steps and history.

## Settings (env vars on the API service)

Required ones are listed in [`backend/.env.example`](backend/.env.example). Optional, with defaults:

| Variable | Default | Meaning |
|---|---|---|
| `TIMEZONE` | `Asia/Jerusalem` | |
| `MORNING_TIME` / `CHECKIN_TIMES` / `EVENING_TIME` | `07:30` / `13:00,17:00` / `21:30` | daily messages |
| `QUIET_START` / `QUIET_END` | `23:00` / `07:00` | no proactive messages at all in between |
| `NAG_INTERVAL_MIN` / `MAX_NAGS` | `30` / `4` | default follow-up interval (faster for higher priority) and when to stop |
| `MEETING_LEAD_MIN` | `15` | reminder before a meeting (plus one at start) |
| `MIN_GAP_MIN` | `20` | minimum minutes between proactive messages (meetings exempt) |
| `MAX_PROACTIVE_PER_DAY` | `20` | proactive messages per chat day |
| `MAX_PER_TASK_PER_DAY` | `4` | assistant check-ins per task per day |
| `FOLLOWUP_MIN_MIN` / `FOLLOWUP_MAX_DAYS` | `10` / `7` | clamp for follow-ups chosen by the LLM |
| `DAY_RESET_TIME` | `04:00` | when the chat "day" starts |
| `REPLY_LINK_HOURS` | `3` | how long a check-in stays the likely topic of your replies |
| `CHAT_PER_MINUTE` / `LLM_CALLS_PER_DAY` | `15` / `300` | spend guards |
| `LLM_TIMEOUT_S` / `LLM_MAX_TOKENS` | `25` / `500` | per LLM call |

## Local development

```bash
cd backend
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt pytest
python -m pytest -q                          # no DB or network needed
python scripts/simulate_tick.py              # a simulated morning: ticks, grouping, a reply, a meeting
python scripts/simulate_tick.py --llm-down   # same with OpenRouter "unreachable" → templates
cp .env.example .env   # fill in, then export the variables
uvicorn app.main:app --reload

# web app (another terminal)
cd web
SUPABASE_URL=... SUPABASE_ANON_KEY=... API_URL=http://localhost:8000 sh build-config.sh
python -m http.server 5173   # set WEB_ORIGIN=http://localhost:5173 for the backend
```

## Project layout

```
backend/app/
  main.py        API routes
  scheduler.py   /tick: plan (guardrails, lease) + background delivery, daily jobs
  coach.py       writes check-ins / meeting reminders / daily plans (LLM + template fallback)
  agent.py       chat agent: likely-topic candidates, linking turns to tasks
  tools.py       the 9 tools, validation, text tool-call parsing
  llm_loop.py    tool-calling loop (one retry, one text-call recovery)
  context.py     compact plain-text context for the LLM
  logic.py       pure scheduling logic and guardrails (tested)
  tasks.py       task storage, events (interaction log)
  thread.py      the chat thread, task links, guardrail counts
  actions.py     Done / Snooze / Cancel + chat events
  prompting.py   loads prompts/*.md
  prompts/       system, coach (the skill), checkin, daily_morning/checkin/evening
  llm.py         OpenRouter client: fallback models, daily cap, timeout, 429 → fallback
  notify.py      web push + Pushover
  auth.py        owner-only login check, cron secret
  tokens.py      signed notification-button tokens
backend/tests/   unit + end-to-end tests (in-memory store, scripted LLM)
db/schema.sql    full schema;  db/migrations/  idempotent upgrades
web/             static web app (installable), service worker for push
render.yaml      Render Blueprint
```
