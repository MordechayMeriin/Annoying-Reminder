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
                                        ▲ every 5 min          └──▶ Pushover (loud, alarms)
                                  cron-job.org (X-Cron-Secret)
```

**Code decides WHEN, the LLM decides WHAT.** Timing, limits and safety are deterministic code; the LLM
writes the messages and can adjust follow-ups through tools, within those limits.

### The chat is the product
- Every proactive contact (task check-in, meeting reminder, morning plan, mid-day check-in, evening
  review) is first saved as an assistant message in the chat, then pushed. The notification body is
  that message; tapping it opens the chat at that message with the reply box focused.
- While you wait for a reply you see what's happening: your message shows *Sending…* → *✓ Sent*,
  typing dots while the LLM works, and a status line for each action it takes ("📝 Writing a new
  task: Dentist"). `POST /chat` streams these as Server-Sent Events; the reply itself arrives whole.
  The turn runs on the server even if you close the app, and the reply shows up on the next refresh.
- ✓ Done / ⏰ Snooze buttons appear when a message is about exactly one task. Done / Snooze from a
  notification or the task list also writes a short event into the chat (`[Marked done: …]`).
- **Daily reset:** the assistant only sees today's chat (from `DAY_RESET_TIME`, default 04:00). Long-term
  memory lives on the tasks: each task's **description** is its current state (rewritten by the
  assistant whenever you tell it something new, editable in the task drawer), and its **history**
  (`task_events`) records postponed / committed / progress / stuck / blocked / ignored / done.

### Each tick (every 5 minutes)
1. Tasks whose `remind_at` has passed are due for contact.
2. Guardrails, in code, before any LLM call ([`logic.contact_decision`](backend/app/logic.py)):
   - Shabbat / holidays and quiet hours are never overruled, whatever the priority (meetings included)
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
6. The push is as loud as its most pressing task (see [Pushover](#pushover-alerts-that-get-your-attention)):
   normal → urgent → a repeating alarm for important tasks you keep ignoring.

The `/tick` request itself only decides and returns; the LLM work runs in a background task.

### Pushover: alerts that get your attention
Chrome notifications are easy to miss: one quiet buzz, same as every other app. With
[Pushover](https://pushover.net) (one-time purchase per platform after a 30-day trial) every message
also gets a **level**, decided by code ([`logic.alert_level`](backend/app/logic.py)):

| Level | When | On the phone |
|---|---|---|
| **Normal** | daily messages, meeting digests, the first check-in about an ordinary task | normal sound |
| **Urgent** | meeting reminders, reminders you asked for ("remind me at 5"), important/critical tasks, and any check-in after one was ignored | the urgent sound (default *Persistent*, long), highlighted, ignores the Pushover app's own quiet hours |
| **Alarm** | an important or critical task ignored twice in a row (if check-ins would stop sooner, the last one) | **repeats every 2 minutes** until you acknowledge it, for up to an hour |

- "Ignored" means no message from you in the chat since that check-in. Replying resets the ladder.
- An alarm stops by itself as soon as you **write in the chat or tap ✓ / ⏰ / cancel** on any task: you've
  seen it. It also never repeats into quiet hours or Shabbat: it ends before they start, and if there
  isn't time for even one repeat, it's sent as urgent instead.
- The guardrails above still decide *whether* something is sent; the level only decides how loud.
- **Send to Pushover** (⚙ → Pushover): *every message* (default; browser push too), *instead of browser*
  push (falls back to browser push if Pushover fails), *as a backup* (only when no browser got it, plus
  alarms), or *never*. The sounds, when check-ins turn urgent, what gets alarms (critical /
  important / every task / nothing) and how often they repeat are all there, plus test buttons.

Setup: see [step 6](#6-pushover-recommended).

### Extra meeting reminders
On top of the reminder before each meeting and at its start, two digests (built by code, no LLM):
- **Start of the week** (default Sunday 08:00): every meeting in the coming 7 days.
- **The evening before** (default 20:00): every meeting tomorrow.

Each is one chat message (linked to those meetings, so a reply like "move the dentist to 10" knows
what you mean) and one push. Nothing is sent when there are no meetings. Recurring meetings are included.

### Shabbat & holidays
From candle lighting to havdalah nothing is sent: no check-ins, meeting reminders, daily messages
or Pushover alerts ([`jewish_calendar.py`](backend/app/jewish_calendar.py), using the
[`hdate`](https://pypi.org/project/hdate/) library, offline). Defaults: Rosh HaAyin, candle lighting
20 min before sunset, havdalah at nightfall, and quiet from 1 hour before candle lighting until
1 hour after havdalah. A holiday next to Shabbat (e.g. Rosh Hashana on Friday-Saturday) is one period.
Only Shabbat and yom tov count, not chol hamoed or fast days.
- Anything that comes due is held and sent as **one** message when the quiet time ends.
- A daily message whose time falls inside it is skipped that day, unless its window reaches past the
  end (e.g. Saturday's 20:00 "tomorrow's meetings" in summer arrives right after Shabbat).
- Your own messages are still answered.
- The settings screen shows the next quiet time, so you can check it.

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

### 6. Pushover (recommended)
1. Install the Pushover app and create an account. Your **User Key** is on the app's / website's
   home screen → `PUSHOVER_USER`.
2. <https://pushover.net/apps/build> → create an application (e.g. "Annoying Reminder") → its
   **API Token** → `PUSHOVER_TOKEN`. Add both on Render (api service → Environment).
3. In the app: ⚙ → Pushover → **Normal / Urgent / Alarm** test buttons, to hear each one.
4. So alarms get through Do Not Disturb:
   - Android: Pushover app → Settings → enable **Bypass Do Not Disturb** for emergency priority (and
     allow it when Android asks); also set its battery usage to **Unrestricted**.
   - iPhone: allow **Critical Alerts** for Pushover when asked (Settings → Notifications → Pushover).

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

**⚙** opens the settings (times, delays, limits, Shabbat, Pushover). The ☑ Tasks drawer lists today's and all open tasks (✓ / ⏰ on each row). Tap a task to see and edit
its description, steps and history.

## Settings

### In the app (⚙)
Times, delays and limits are edited in the app: **⚙ → Settings**. They're validated by the server
and stored in the `app_settings` table, and apply from the next tick (within 5 minutes).
↺ next to a setting puts it back to its default.

| Group | Settings (defaults) |
|---|---|
| Daily messages | time zone (`Asia/Jerusalem`), morning plan (`07:30`), mid-day check-ins (`13:00, 17:00`), evening review (`21:30`), new chat day starts at (`04:00`) |
| Quiet hours | `23:00` – `07:00`: no proactive messages at all |
| Shabbat & holidays | on; location (Rosh HaAyin), outside Israel (off), candle lighting (`20` min before sunset), havdalah (`0` = nightfall), quiet before / after (`60` / `60` min) |
| Meetings | reminder before (`15` min, plus one at start), first day of the week (Sunday), this week's meetings at (`08:00`), tomorrow's meetings at (`20:00`); either digest can be off |
| Check-ins & limits | follow-up interval (`30` min) and stop after (`4`), minimum gap between messages (`20` min, meetings exempt), messages per day (`20`), check-ins per task per day (`4`), follow-ups the assistant may pick (`10` min – `7` days), a reply refers to the last check-in for (`3` h) |
| Pushover | send to Pushover (every message), sound: normal (app default) / urgent (`Persistent`), urgent after (`1`) ignored check-in, alarm for (important + critical tasks) after (`2`) ignored check-ins, alarm repeats every (`2` min) |

The defaults can also come from env vars with the same name in capitals (e.g. `MORNING_TIME`), but
there's no need: you can remove them from Render. A setting saved in the app wins over the env var.

### On the server only (Render env vars)
Secrets and URLs are listed in [`backend/.env.example`](backend/.env.example). These spend guards also
stay server-side, so they still cap OpenRouter spend even if the login were ever compromised:

| Variable | Default | Meaning |
|---|---|---|
| `CHAT_PER_MINUTE` / `LLM_CALLS_PER_DAY` | `15` / `300` | spend guards |
| `LLM_TIMEOUT_S` / `LLM_MAX_TOKENS` | `25` / `500` | per LLM call |
| `LLM_REASONING_EFFORT` | empty | `minimal` / `low` / `medium` / `high` for reasoning models (empty = provider default). Reasoning tokens may count toward `LLM_MAX_TOKENS` |

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
  coach.py       writes check-ins / meeting reminders / daily plans (LLM + template fallback),
                 meeting digests (week ahead, tomorrow)
  jewish_calendar.py  Shabbat / holiday quiet periods (hdate library, pure)
  config.py      settings: env-only secrets + the editable behaviour settings (SPECS)
  user_settings.py    behaviour settings saved from the app (app_settings table)
  agent.py       chat agent: likely-topic candidates, linking turns to tasks
  tools.py       the 9 tools, validation, text tool-call parsing
  llm_loop.py    tool-calling loop (one retry, one text-call recovery)
  context.py     compact plain-text context for the LLM
  logic.py       pure scheduling logic, guardrails and alert levels (tested)
  tasks.py       task storage, events (interaction log)
  thread.py      the chat thread, task links, guardrail counts
  actions.py     Done / Snooze / Cancel + chat events
  prompting.py   loads prompts/*.md
  prompts/       system, coach (the skill), checkin, daily_morning/checkin/evening
  llm.py         OpenRouter client: fallback models, daily cap, timeout, 429 → fallback
  notify.py      web push + Pushover (levels, alarms, cancelling them)
  auth.py        owner-only login check, cron secret
  tokens.py      signed notification-button tokens
backend/tests/   unit + end-to-end tests (in-memory store, scripted LLM)
db/schema.sql    full schema;  db/migrations/  idempotent upgrades
web/             static web app (installable), service worker for push
render.yaml      Render Blueprint
```
