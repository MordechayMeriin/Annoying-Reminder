# 🔔 Annoying Reminder

A personal, LLM-powered ADHD assistant. It doesn't just remind: it helps you **start, keep going and
come back**, with less effort than a to-do app. It's one ongoing chat: you say a task once, in plain
Hebrew or English, and it works out the first step, proposes Today's 3 every morning, checks in with
one-tap reply buttons, and decides sensibly when to follow up.

**The one rule: the app does the work.** Nothing asks you to fill in fields, sort lists or answer
questions just to keep it running. Every question comes with buttons, silence means "accept the
default", starting and finishing work from the notification, and new features replace messages
rather than add to them. (The plan is in [the implementation brief](<Annoying Reminder → ADHD Assistant Implementation Brief.md>).
Phase 1 is in: reply buttons, the silent first step, Today's 3 and playbooks. Phase 2 is in: What now?,
focus sessions with a timer, "where did you stop?", sharing / dictating tasks, and the day's wins.
Phase 3 is in: gentler pressure (avoided tasks, de-escalation, re-entry after a lapse), the Sunday
cleanup, hard stops ("fences"), and settings / preferences said in chat.)

## How it works

```
 Phone (Chrome web app)                Render (free)                         Supabase (free)
 ┌──────────────────────┐   HTTPS   ┌──────────────────────────┐   SQL    ┌──────────────┐
 │ chat (main screen)   │ ────────▶ │ FastAPI                  │ ───────▶ │ Postgres     │
 │ Today's 3 · tasks    │ ◀──push── │  • agent  (OpenRouter)   │          │ (RLS on)     │
 │ [▶ Start] [✓ Done]   │           │  • /tick → coach         │          └──────────────┘
 │ ⏱ timer · What now?  │           │  • sessions, What now?   │
 └──────────────────────┘           └──────────────────────────┘
                                        ▲ every 5 min          └──▶ Pushover (loud, alarms)
                                  cron-job.org (X-Cron-Secret)
```

**Code decides WHEN, WHICH playbook and WHICH buttons. The LLM writes the words.** Timing, limits,
safety, the reply buttons and which coaching playbook applies are deterministic code; the LLM writes
the messages and can adjust follow-ups through tools, within those limits.

### Task stages
Every task has a stage ([`logic.task_stage`](backend/app/logic.py)), and the app's move depends on it:
**captured** (you said it) → **clarified** (it has a next action, filled in silently in the same turn) →
**planned** (in Today's 3) → **in session** (you tapped ▶ Start: a timer runs) → **done**. A session you
stop, or don't answer at its end, leaves the task **paused**, with an optional "where did you stop?". A
task postponed or ignored 3+ times in 7 days is **avoided**: the nagging stops
(see [Gentler pressure](#gentler-pressure)).

### Reply buttons ("chips")
Every proactive message, and every chat reply about exactly one open task, comes with 2–4 buttons,
chosen by code from the message kind and the task's stage ([`logic.chips_for`](backend/app/logic.py)),
in Hebrew or English (the language of your latest message). They show only under the latest message
and disappear once you reply.

| Message | Buttons |
|---|---|
| Task check-in | ▶ Start 5 min · ✓ Done · Later · I'm stuck |
| Morning plan | ▶ Start the first one · Change it |
| Mid-day check-in | ▶ Start next · What now? |
| Session end | ✓ Done · ▶ Another round · Stop |
| What now? answer | ▶ Start · Low energy · Something else |
| Chat reply about one open task | ▶ Start 5 min · ✓ Done |
| Meeting reminder, evening review | none |

- **Action buttons run without the LLM** (Start, Done, Later, Another round, Stop, What now?, Low
  energy, Something else): they change the task or ask the code picker, and write a short event in the
  chat (`[▶ 5 min: …]`, `[What now?]`). **Text buttons** (I'm stuck, Change it) send their label as
  your reply, and the assistant answers.
- **Later** is decided by code: +2 hours before 18:00, otherwise the next morning.
- **▶ Start** opens a [focus session](#focus-sessions) on the next action: 5 minutes from a
  notification or a button, 25 from the focus card.
- Task check-ins carry **▶ Start 5 min** and **✓ Done** as notification buttons, a session's end
  carries **✓ Done** and **▶ Another round**, so the most common actions work from the notification.
- **Taps count as activity**, like a typed reply: a user who only taps is never "ignoring" the app.

### Today's 3
Every morning the plan proposes up to three focus tasks (the LLM picks them with `set_focus`; if it
can't, code picks: overdue, then due today, then priority) and **saves them right away: silence accepts
the plan.** "Change it" / "swap 2 for the dentist" in the chat changes them. The **focus card** at the
top of the chat shows them with their next action and ▶ Start; done ones get a tick.

Fewer messages: during the day, check-ins go only to Today's 3, tasks due within 48 hours, reminders
you asked for and meetings. Undated tasks outside Today's 3 aren't nudged; they compete for tomorrow's
focus instead. Mid-day check-ins are skipped when you were active, or a session ran, in the last hour.

The card's head shows the day's **wins**: "Done today: 4 · ⏱ 2 sessions" (finished steps and recurring
tasks count; no streaks, no zeros). The evening review leads with them too.

### What now?
Tap **What now?** (on the focus card or the mid-day check-in) and code picks **one** thing, in under a
second, with no LLM ([`logic.pick_next`](backend/app/logic.py)): the first of Today's 3 not done yet, if
its estimate × 1.5 fits before your next meeting; otherwise the shortest one that fits; if nothing fits,
5 minutes of the first. The answer is "Do this: {first step} ({n} min)" with ▶ Start · Low energy ·
Something else. **Low energy** picks the smallest task estimated at 15 minutes or less (or 5 minutes of
the top one); **Something else** gives the next candidate. With no focus tasks, it picks by due date,
then priority, then age; when all of Today's 3 are done, it says so and offers one bonus task.

### Focus sessions
**▶ Start** turns a nudge into a timer: a full-screen ring with the next action, where you stopped last
time, ✓ Done · Stop · +5, and one tap to switch between 5 / 15 / 25 / 45 minutes. ✕ hides it (a bar in
the header keeps counting). From a notification, ▶ Start opens the running timer right away.
- At the end, one check-in arrives at the first tick (within 5 minutes): a template, so it costs
  nothing and never fails, with ✓ Done · ▶ Another round · Stop. If the app is open, the ring also ends
  on time with a sound.
- **Silence is an answer:** no reply 15 minutes after the end check-in records the session as
  *paused*; nothing more is sent and the task stays in Today's 3.
- **Focus mode:** while a session runs, other check-ins wait until it ends + 10 minutes. Meetings still
  come through. The task's own check-in inside the session is replaced by the end check-in.
- Session messages count toward the daily total but skip the minimum gap and the per-task cap (you
  asked for them). Quiet hours and Shabbat still hold: an end that falls inside them isn't sent.
- Each session records planned and actual minutes (until the ✓ / Stop tap; the planned time when
  silent), for the success measures and, later, for learning how long things really take.

### Where did you stop?
After you tap Stop, or don't answer a session's end, a small optional field appears in the chat:
"Where did you stop? (optional)", with a mic. Doing nothing skips it (it goes away after a few hours).
You can also just say it in the chat ("I stopped at page 3"). The line is shown on the next session
screen and used by the next check-in ("You stopped at row 40"), and it's cleared when the task is done.

### Capture without typing
- **Share** text from any app (WhatsApp, email, a link) to *Reminder*: it goes into the chat and the
  assistant saves it as a task, with its first step. (The installed app is a share target.)
- **🎤** next to the reply box dictates, in Hebrew or English (the language of your latest message).
- Long-press the app icon → **New task** opens the chat ready to type or dictate.

### The chat is the product
- Every proactive contact (task check-in, meeting reminder, morning plan, mid-day check-in, evening
  review) is first saved as an assistant message in the chat, then pushed. The notification body is
  that message; tapping it opens the chat at that message with the reply box focused.
- While you wait for a reply you see what's happening: your message shows *Sending…* → *✓ Sent*,
  typing dots while the LLM works, and a status line for each action it takes ("📝 Writing a new
  task: Dentist"). `POST /chat` streams these as Server-Sent Events; the reply itself arrives whole.
  The turn runs on the server even if you close the app, and the reply shows up on the next refresh.
- Reply buttons appear under the latest message (see [Reply buttons](#reply-buttons-chips)). An action
  from a button, a notification or the task list also writes a short event into the chat
  (`[Marked done: …]`).
- **Daily reset:** the assistant only sees today's chat (from `DAY_RESET_TIME`, default 04:00). Long-term
  memory lives on the tasks: each task's **description** is its current state (rewritten by the
  assistant whenever you tell it something new, editable in the task drawer), and its **history**
  (`task_events`) records postponed / committed / progress / stuck / blocked / ignored / done.

### Gentler pressure
The more you avoid something, the quieter the app gets about it, not the louder: pressure on an avoided
task feeds shame, and shame feeds avoidance. All of it is decided by code ([`logic.py`](backend/app/logic.py)).

- **Avoided tasks.** After 3 postponed or ignored check-ins in 7 days, ordinary check-ins about the task
  stop. You get **one** message ("Taxes keeps slipping, and that's fine. What's in the way?") with
  **Too big · Unclear · Boring · Not now**, at normal volume. A reason tap is recorded by code and the
  assistant applies the matching move (Too big → three steps, with ▶ Start 5 min on the first; Unclear →
  the one question that would unblock it; Boring → a 5-minute start). **Not now** parks the task for 7
  days: no check-ins, out of Today's 3. No answer: one more try 3 days later, then it's parked without a
  word. A reason, a started session or progress is a fresh start; so is the end of a park. (A task
  whose deadline is coming up within 48 hours keeps its ordinary check-ins.)
- **De-escalation.** Only meetings, hard stops, reminders you asked for and tasks with a deadline within
  48 hours (coming up, or missed that recently) can be urgent or alarms, or come more than once a day.
  Every other task gets one normal check-in a day at most, whatever its priority.
- **Re-entry after a lapse.** 48 hours with no chat message and no tap (Shabbat and holidays don't
  count) stop task check-ins, the mid-day check-ins and the evening review. The next morning message is
  the re-entry message, once: "Good to see you. Want me to pick one thing for today?" with
  **Pick one for me · Move overdue to next week**. No counts, no overdue list. Meeting reminders, hard
  stops and reminders you asked for continue. One tap or one message and everything is back to normal.
  (A lapse that goes on gets the message again a week later, not every morning.)
- **Sunday.** The first morning plan of the week carries the week's meetings (instead of a separate
  digest: one message fewer), one line of last week's wins, and up to three tasks open for 3+ weeks
  with nothing moving, with **Drop them · Keep**. Silence keeps them, and they aren't offered again for
  4 weeks. There's no weekly review to do.
- **Hard stops ("fences").** Say it in passing ("I pick up the kids at 17:00 every weekday") and it's
  saved as a hard stop. At that time you get "⛔ 17:00 — Pick up the kids. Time to stop.", urgent,
  through a running focus session and the minimum gap; never through quiet hours or Shabbat (a stop
  that couldn't be sent within 30 minutes of its time is skipped). What now? never picks something
  longer than the time to the next one. They're in the task list (⛔) and cancelled like any task.

### Each tick (every 5 minutes)
1. Tasks whose `remind_at` has passed are due for contact, if they may get check-ins at all (Today's 3,
   due within 48 hours, reminders you asked for, meetings and hard stops; see [Today's 3](#todays-3)).
   Parked tasks never are; during a lapse only meetings, hard stops and reminders you asked for.
2. Guardrails, in code, before any LLM call ([`logic.contact_decision`](backend/app/logic.py)):
   - Shabbat / holidays and quiet hours are never overruled, whatever the priority (meetings included)
   - while a focus session runs (until it ends + 10 min) only meetings, hard stops and its own end
     check-in are sent
   - `MIN_GAP_MIN` between proactive messages (meetings and hard stops exempt)
   - `MAX_PROACTIVE_PER_DAY` messages per day (meetings and hard stops skip it but count toward it)
   - `MAX_PER_TASK_PER_DAY` assistant check-ins per task with a deadline within 48 hours; **one a day**
     for every other task (reminders you asked for don't count)
   - blocked tasks wait until their follow-up time
   - when several rules apply, the latest postponement wins
3. Everything due in the same tick becomes **one** message and **one** push. An avoided task isn't in
   it: its reason message goes alone, when nothing else is due.
4. The coach writes it with the LLM from a compact context (next action, description, steps, history,
   linked messages, today's chat), with the playbook code chose and the buttons the message gets. It
   may call `schedule_followup`; otherwise the next check-in follows the priority-based nag interval.
   Meeting times always come from code.
5. **Fallback:** if the LLM errors, times out (`LLM_TIMEOUT_S`), returns nothing, hits a 429 or the
   daily cap, a template is sent instead, leading with the next action ("▶ Find last year's PDF —
   Taxes"). A reminder is never lost because of the LLM.
6. The push is as loud as its most pressing task (see [Pushover](#pushover-alerts-that-get-your-attention)):
   normal → urgent → a repeating alarm, and only for what may escalate (see [Gentler pressure](#gentler-pressure)).
7. Focus sessions whose time is up get their end check-in (urgent, like a reminder you asked for);
   those nobody answered for 15 minutes are recorded as paused.

The `/tick` request itself only decides and returns; the LLM work runs in a background task.

### Pushover: alerts that get your attention
Chrome notifications are easy to miss: one quiet buzz, same as every other app. With
[Pushover](https://pushover.net) (one-time purchase per platform after a 30-day trial) every message
also gets a **level**, decided by code ([`logic.alert_level`](backend/app/logic.py)):

| Level | When | On the phone |
|---|---|---|
| **Normal** | daily messages, meeting digests, every check-in about a task with no deadline within 48 hours (however often it was ignored), the reason message about an avoided task | normal sound |
| **Urgent** | meeting reminders, hard stops, the end of a focus session, reminders you asked for ("remind me at 5"); for tasks with a deadline within 48 hours: important/critical ones, and any check-in after one was ignored | the urgent sound (default *Persistent*, long), highlighted, ignores the Pushover app's own quiet hours |
| **Alarm** | an important or critical task **with a deadline within 48 hours** (or a reminder you asked for) ignored twice in a row (if check-ins would stop sooner, the last one) | **repeats every 2 minutes** until you acknowledge it, for up to an hour |

- "Ignored" means no message and no tap from you in the chat since that check-in. Replying or tapping
  a button resets the ladder.
- An alarm stops by itself as soon as you **write in the chat or tap ✓ / ⏰ / cancel** on any task: you've
  seen it. It also never repeats into quiet hours or Shabbat: it ends before they start, and if there
  isn't time for even one repeat, it's sent as urgent instead.
- The guardrails above still decide *whether* something is sent; the level only decides how loud.
- **Send to Pushover** (⚙ → Notifications): *every message* (default; browser push too), *instead of browser*
  push (falls back to browser push if Pushover fails), *as a backup* (only when no browser got it, plus
  alarms), or *never*.
- **Browser notifications** (⚙ → Notifications) can be switched off altogether: then every message goes
  to Pushover, whatever *Send to Pushover* says, with no browser fallback, and the app stops offering
  "Enable notifications". While Pushover isn't set up, browser push keeps working anyway, so reminders
  are never lost. Without the `VAPID_*` keys there are no browser notifications at all. The sounds, when check-ins turn urgent, what gets alarms (critical /
  important / every task / nothing) and how often they repeat are all there, plus test buttons.

Setup: see [step 6](#6-pushover-recommended).

### Extra meeting reminders
On top of the reminder before each meeting and at its start, two digests (built by code, no LLM):
- **Start of the week** (default Sunday 08:00): every meeting in the coming 7 days. When that time is
  within an hour of the morning plan (it is by default), the list goes at the end of that day's morning
  plan instead of a message of its own.
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

### Tools (12, flat parameters for a mid-size model)
`add_task` (with the inferred `next_action` and `estimate_min`), `list_tasks`, `update_task` (also
description / next action / where you stopped / unblock), `complete_task`, `cancel_task`, `schedule_followup`,
`add_subtask`, `log_interaction` (also why a task is avoided), `mark_blocked`, `set_focus` (Today's 3),
`update_setting` (any setting under ⚙, validated by the same code as the settings screen) and
`remember_preference`. `add_task` also saves hard stops (`kind=fence`). Each context gets only what
it needs: the morning plan gets `set_focus` and `list_tasks`, check-ins `schedule_followup`, chat all.

### Say it instead of setting it
"Don't message me after 22:00" changes the quiet hours at once, with one line back ("Done: quiet hours
now start at 22:00 (was 23:00). Say 'undo' to revert."). No confirmation, because it's reversible.
Things that aren't settings ("shorter messages please", "no emojis") are remembered as preferences:
they're added to every prompt (`{preferences}`), and listed under ⚙ → *What you asked for*, where ✕
forgets one.

### Prompts are files: a short core plus playbooks
[`backend/app/prompts/`](backend/app/prompts/): `core.md` (who the assistant is and the communication
rules, in every prompt) and [`playbooks/`](backend/app/prompts/playbooks/) (`planning`, `stuck`,
`avoiding`, `overwhelmed`, `reentry`, `celebrate`, `sunday`). Code picks at most two per call
([`logic.select_playbooks`](backend/app/logic.py)): the morning gets `planning`, an avoided task
`avoiding`, an "I'm stuck" tap or a stuck event today `stuck`, the first morning of the week `sunday`,
the message after a lapse `reentry`, and so on; with no signal, the core only.
Smaller prompts get better behaviour from cheaper models. `system.md` (chat), `checkin.md` and
`daily_*.md` say what each message is for. Edit the files to change the style, no code changes.

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
   [`db/migrations/`](db/migrations/) in order instead; they are idempotent. After `006`, give your
   existing tasks their first steps once: Render → api service → Shell →
   `python scripts/backfill_next_actions.py`. `007` adds two columns for focus sessions, `008` three
   columns for avoided tasks.)
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

Type, or tap a button. For example:
- `Meeting with Dani tomorrow 14:00`
- `לשלם ארנונה עד יום ראשון, חשוב`
- `Call mom every Friday 12:00`
- `I'm stuck on the tax form` → it asks one question or suggests a 10–20 minute first step, and can
  break the task into steps
- `Later, I'll do it at 5` → it remembers the commitment and checks in at 17:00
- `Waiting for the landlord to answer` → marked blocked, it asks again later
- `What's left for today?`
- `Swap 2 for the dentist` → changes Today's 3
- `I stopped at page 3` → remembered for the next session on that task
- `I pick up the kids at 17:00 every weekday` → a hard stop: it interrupts you then, even mid-session
- `Don't message me after 22:00` → the setting changes, no screen visited; `undo` reverts it
- `Shorter messages please` → remembered, and followed from then on
- Or don't type at all: share a WhatsApp message to the app, or tap 🎤

**⚙** opens the settings (times, delays, limits, Shabbat, Pushover). The ☑ Tasks drawer lists today's and all open tasks (✓ / ⏰ on each row), each with its next action. Tap a task to see and edit
its description, steps and history.

## Settings

### In the app (⚙)
Times, delays and limits are edited in the app: **⚙ → Settings**. They're validated by the server
and stored in the `app_settings` table, and apply from the next tick (within 5 minutes). Saying it in
the chat does the same thing (see [Say it instead of setting it](#say-it-instead-of-setting-it)).
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
| `CHAT_TURN_TIMEOUT_S` | `60` | a whole chat turn (all its LLM calls and tools); then it stops with a message |
| `LLM_REASONING_EFFORT` | empty | `minimal` / `low` / `medium` / `high` for reasoning models (empty = provider default). Reasoning tokens may count toward `LLM_MAX_TOKENS` |

## Local development

```bash
cd backend
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt pytest
python -m pytest -q                          # no DB or network needed
python scripts/simulate_tick.py              # a simulated morning: ticks, a reply, What now?, a session, a meeting
python scripts/simulate_tick.py --llm-down   # same with OpenRouter "unreachable" → templates
DATABASE_URL=... python scripts/metrics.py   # success measures for the last 28 days (read-only)
                                             #   --from 2026-10-01 --to 2026-11-01 for a range
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
  scheduler.py   /tick: plan (guardrails, lease, avoided tasks, the lapse) + background delivery, daily jobs
  coach.py       writes check-ins / meeting reminders / daily plans (LLM + template fallback),
                 meeting digests (week ahead, tomorrow), the re-entry message, the Sunday lines, fences
  jewish_calendar.py  Shabbat / holiday quiet periods (hdate library, pure)
  config.py      settings: env-only secrets + the editable behaviour settings (SPECS)
  user_settings.py    behaviour settings saved from the app or the chat (app_settings table)
  preferences.py      what you asked of the assistant in chat (preferences table), in every prompt
  agent.py       chat agent: likely-topic candidates, playbooks, linking turns to tasks, reply buttons
  tools.py       the 12 tools, validation, text tool-call parsing
  llm_loop.py    tool-calling loop (one retry, one text-call recovery)
  context.py     compact plain-text context for the LLM
  logic.py       pure logic (tested): scheduling, guardrails, alert levels, reply buttons, stages,
                 playbook choice, Today's 3 pick, Later, What now? pick, session timing, focus hold,
                 avoided / parked, de-escalation, the lapse, fences, the Sunday merge
  tasks.py       task storage, events (interaction log)
  thread.py      the chat thread, task links, guardrail counts
  sessions.py    focus sessions storage (the timer, its end check-in, breadcrumbs)
  actions.py     Done / Start / Another round / Stop / Later / Snooze / Cancel / Not now, What now?,
                 Move overdue / Drop them / Keep (no LLM) + chat events
  prompting.py   loads prompts/*.md
  prompts/       core + playbooks/, system, checkin, daily_morning/checkin/evening/reentry
  llm.py         OpenRouter client: fallback models, daily cap, timeout, 429 → fallback
  notify.py      web push + Pushover (levels, alarms, cancelling them)
  auth.py        owner-only login check, cron secret
  tokens.py      signed notification-button tokens
backend/tests/   unit + end-to-end tests (in-memory store, scripted LLM)
backend/scripts/ simulate_tick (a simulated morning and week), metrics (success measures),
                 backfill_next_actions (one-off after migration 006), gen_secrets
db/schema.sql    full schema;  db/migrations/  idempotent upgrades
web/             static web app (installable, a share target), service worker for push
render.yaml      Render Blueprint
```
