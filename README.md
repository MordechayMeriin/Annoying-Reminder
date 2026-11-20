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

**It's an Android app** ([`android/`](android/README.md)). The phone keeps your data, decides when to remind you
and runs the assistant, offline included. A small AWS Lambda function ([`backend/`](backend/app/lambda_app.py))
holds the LLM key and the backup. It started as a web app on Render; how it moved is in
[the Android plan](docs/android-plan.md) and the `docs/android-phase-*.md` files.

## How it works

```
 Galaxy S9 (the brain, works offline)                       AWS (always-free tier)
 ┌───────────────────────────────────────────┐  HTTPS   ┌──────────────────────────────┐
 │ chat (main screen) · Today's 3 · tasks    │ ───────▶ │ Lambda (function URL)        │
 │ [▶ Start] [✓ Done] · ⏱ timer · What now?  │          │  • /auth/google  login       │──▶ DynamoDB
 │ Room database: the live copy of your data │  daily   │  • /sync         the backup  │    (the backup)
 │ the tick: guardrails → coach → notify     │ ───────▶ │  • /llm          the key     │──▶ OpenRouter
 │ AlarmManager: wakes for the next thing    │  backup  │  • daily: phone alive?       │──▶ email (SES)
 │ notifications: normal · urgent · alarm    │          └──────────────────────────────┘
 └───────────────────────────────────────────┘                ▲ deployed by GitHub Actions (OIDC)
```

**Code decides WHEN, WHICH playbook and WHICH buttons. The LLM writes the words.** Timing, limits,
safety, the reply buttons and which coaching playbook applies are deterministic code; the LLM writes
the messages and can adjust follow-ups through tools, within those limits.

### Task stages
Every task has a stage ([`Coaching.taskStage`](android/app/src/main/java/com/prominder/myapp/logic/Coaching.kt)), and the app's move depends on it:
**captured** (you said it) → **clarified** (it has a next action, filled in silently in the same turn) →
**planned** (in Today's 3) → **in session** (you tapped ▶ Start: a timer runs) → **done**. A session you
stop, or don't answer at its end, leaves the task **paused**, with an optional "where did you stop?". A
task postponed or ignored 3+ times in 7 days is **avoided**: the nagging stops
(see [Gentler pressure](#gentler-pressure)).

### Reply buttons ("chips")
Every proactive message, and every chat reply about exactly one open task, comes with 2–4 buttons,
chosen by code from the message kind and the task's stage ([`ChipRules.chipsFor`](android/app/src/main/java/com/prominder/myapp/logic/ChipRules.kt)),
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
second, with no LLM ([`Focus.pickNext`](android/app/src/main/java/com/prominder/myapp/logic/Focus.kt)): the first of Today's 3 not done yet, if
its estimate × 1.5 fits before your next meeting; otherwise the shortest one that fits; if nothing fits,
5 minutes of the first. The answer is "Do this: {first step} ({n} min)" with ▶ Start · Low energy ·
Something else. **Low energy** picks the smallest task estimated at 15 minutes or less (or 5 minutes of
the top one); **Something else** gives the next candidate. With no focus tasks, it picks by due date,
then priority, then age; when all of Today's 3 are done, it says so and offers one bonus task.

### Focus sessions
**▶ Start** turns a nudge into a timer: a full-screen ring with the next action, where you stopped last
time, ✓ Done · Stop · +5, and one tap to switch between 5 / 15 / 25 / 45 minutes. ✕ hides it (a bar in
the header keeps counting). From a notification, ▶ Start opens the running timer right away.
- Meanwhile a silent countdown sits in the notification shade, with ✓ Done and Stop.
- At the end, one check-in arrives on time (the phone wakes for it): a template, so it costs nothing and
  never fails, urgent, with ✓ Done · ▶ Another round · Stop.
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
- **Share** text from any app (WhatsApp, email, a link) to *ProMinder*: it goes into the chat and the
  assistant saves it as a task, with its first step.
- **🎤** next to the reply box dictates, in Hebrew or English (the language of your latest message).
- Long-press the app icon → **New task** opens the chat ready to type or dictate.

### The chat is the product
- Every proactive contact (task check-in, meeting reminder, morning plan, mid-day check-in, evening
  review) is first saved as an assistant message in the chat, then notified. The notification body is
  that message; tapping it opens the chat, and **Reply** on the notification answers without opening it.
- While you wait for a reply you see what's happening: your message shows *Sending…* → *✓ Sent*,
  typing dots while the LLM works, and a status line for each action it takes ("📝 Writing a new
  task: Dentist"). The turn runs on the phone and keeps going if you leave the app; only each model call
  goes through the Lambda.
- Reply buttons appear under the latest message (see [Reply buttons](#reply-buttons-chips)). An action
  from a button, a notification or the task list also writes a short event into the chat
  (`[Marked done: …]`).
- **Daily reset:** the assistant only sees today's chat (from `DAY_RESET_TIME`, default 04:00). Long-term
  memory lives on the tasks: each task's **description** is its current state (rewritten by the
  assistant whenever you tell it something new, editable on the task's page), and its **history**
  (`task_events`) records postponed / committed / progress / stuck / blocked / ignored / done.

### Gentler pressure
The more you avoid something, the quieter the app gets about it, not the louder: pressure on an avoided
task feeds shame, and shame feeds avoidance. All of it is decided by code ([`Pressure.kt`](android/app/src/main/java/com/prominder/myapp/logic/Pressure.kt)).

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

### Each tick
The phone works out the next moment something could happen (a `remind_at`, a meeting, a hard stop, a daily
message, a session's end, the end of Shabbat…) and sets one exact alarm for it. When it rings, the tick runs and
sets the next one. A safety-net tick runs every 15 minutes and after a restart, in case Samsung's battery saver
interfered. Each tick:

1. Tasks whose `remind_at` has passed are due for contact, if they may get check-ins at all (Today's 3,
   due within 48 hours, reminders you asked for, meetings and hard stops; see [Today's 3](#todays-3)).
   Parked tasks never are; during a lapse only meetings, hard stops and reminders you asked for.
2. Guardrails, in code, before any LLM call ([`Contacts.contactDecision`](android/app/src/main/java/com/prominder/myapp/logic/Contacts.kt)):
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
6. The notification is as loud as its most pressing task (see [How loud](#how-loud-normal-urgent-alarm)):
   normal → urgent → a repeating alarm, and only for what may escalate (see [Gentler pressure](#gentler-pressure)).
7. Focus sessions whose time is up get their end check-in (urgent, like a reminder you asked for);
   those nobody answered for 15 minutes are recorded as paused.

Diagnostics keeps a log of every tick: what was due, what was sent, and why anything was held back
("skipped: quiet hours", "skipped: min gap, 12 of 20 min"). Share sends it.

### How loud: normal, urgent, alarm
Every message gets a **level**, decided by code ([`Loudness.alertLevel`](android/app/src/main/java/com/prominder/myapp/logic/Loudness.kt)). Each level is a
notification channel, so its sound and vibration are yours to change in Android's settings.

| Level | When | On the phone |
|---|---|---|
| **Normal** | daily messages, meeting digests, every check-in about a task with no deadline within 48 hours (however often it was ignored), the reason message about an avoided task | the normal sound |
| **Urgent** | meeting reminders, hard stops, the end of a focus session, reminders you asked for ("remind me at 5"); for tasks with a deadline within 48 hours: important/critical ones, and any check-in after one was ignored | a long sound, high priority, through Do Not Disturb (you allow that once) |
| **Alarm** | an important or critical task **with a deadline within 48 hours** (or a reminder you asked for) ignored twice in a row (if check-ins would stop sooner, the last one) | a **full-screen alarm** on the alarm volume, like the clock app's: it rings for up to a minute, then **again every 2 minutes** until you answer, for up to an hour |

- "Ignored" means no message and no tap from you in the chat since that check-in. Replying or tapping
  a button resets the ladder.
- An alarm stops by itself as soon as you **write in the chat or tap ✓ / ⏰ / cancel** on any task: you've
  seen it. The alarm screen has ✓ Done · ⏰ Later · Open · Dismiss. It also never repeats into quiet hours or
  Shabbat: it ends before they start, and if there isn't time for even one repeat, it's sent as urgent instead.
- The guardrails above still decide *whether* something is sent; the level only decides how loud.
- **⚙ → How loud**: when check-ins turn urgent, what gets alarms (critical / important / every task / nothing),
  after how many ignored check-ins, and how often they repeat. Diagnostics has test buttons for each level.

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
or alarms ([`RestCalendar.kt`](android/app/src/main/java/com/prominder/myapp/logic/RestCalendar.kt): KosherJava
Zmanim for the Hebrew calendar and hdate's sunset formula, offline). Defaults: Rosh HaAyin, candle lighting
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

### Which model replied
Off by default. **⚙ → App → Show which AI model replied** puts the model behind the latest reply in a
small grey line ("gemini-2.5-flash"), so you can see when
OpenRouter fell back to another model. It's the model OpenRouter says answered, saved on every message
an LLM wrote. Nothing shows when code wrote the latest message (a template, a button's answer).

### Prompts are files: a short core plus playbooks
[`backend/app/prompts/`](backend/app/prompts/): `core.md` (who the assistant is and the communication
rules, in every prompt) and [`playbooks/`](backend/app/prompts/playbooks/) (`planning`, `stuck`,
`avoiding`, `overwhelmed`, `reentry`, `celebrate`, `sunday`). Code picks at most two per call
([`Coaching.selectPlaybooks`](android/app/src/main/java/com/prominder/myapp/logic/Coaching.kt)): the morning gets `planning`, an avoided task
`avoiding`, an "I'm stuck" tap or a stuck event today `stuck`, the first morning of the week `sunday`,
the message after a lapse `reentry`, and so on; with no signal, the core only.
Smaller prompts get better behaviour from cheaper models. `system.md` (chat), `checkin.md` and
`daily_*.md` say what each message is for. Edit the files to change the style, no code changes: the app ships
them, and downloads newer ones from the Lambda at its daily backup, so a merged prompt change reaches the phone
without a new app release.

## Security: only you can use it

| Layer | Protection |
|---|---|
| OpenRouter key | Only on the Lambda function (its environment variables), never on the phone. Use a dedicated key with a monthly credit limit. |
| Login | Google sign-in, for one account (`OWNER_GOOGLE_EMAIL`). The Lambda checks Google's signature, audience and expiry, then gives the app its own login token for a year, signed with a key derived from `ACTION_SECRET`. |
| API | Every request's token is checked (signature, expiry, still the owner); anything else gets 401. |
| Your data | Lives on the phone. The backup in DynamoDB is reached only by the Lambda, through its role: this one table, no Scan. The phone never gets AWS keys. |
| Deploys | GitHub Actions signs in to AWS with OIDC, only from this repository's main branch, and may only update the function's code. No AWS keys are stored in GitHub. |
| Cost | Capacity sized to AWS's always-free tier, and a $1/month budget alert. |
| Spend | A rate limit and a daily LLM-call cap on the Lambda (`LLM_PER_MINUTE`, `LLM_CALLS_PER_DAY`), shared by every instance, so they hold even if the login were compromised. |

## Setup checklist

Everything is in a browser; nothing to install on a computer.

### 1. The backend (AWS Lambda)
1. An AWS account. In the console, pick a region, open **CloudShell** (the `>_` icon at the top) and run:
   ```
   git clone https://github.com/MordechayMeriin/Annoying-Reminder.git && cd Annoying-Reminder/backend
   python3 scripts/aws_lambda_setup.py
   ```
   It asks for `GOOGLE_CLIENT_ID` and `OWNER_GOOGLE_EMAIL` ([how to get them](android/README.md#1-google-sign-in)),
   and your OpenRouter key and models: **Keys → Create key** just for this app with a credit limit (e.g.
   $3/month), and models that support tool calling from <https://openrouter.ai/models>, e.g.
   `google/gemma-4-31b-it:free,openrouter/free`. It creates the table, the function, its address, the daily alive
   check, GitHub's way in and a $1 budget alert, all within the always-free limits. AWS emails you a link to
   verify your address for the alive check: click it. Safe to run again.
2. **GitHub → Settings → Secrets and variables → Actions → Variables**: the three it prints, `AWS_DEPLOY_ROLE_ARN`,
   `AWS_REGION` and `API_URL`.
3. **Actions → Backend → Run workflow** on `main`: it tests, deploys and ends with *Live at https://…*.

### 2. The app
The one-time Google Play setup (developer account, upload key, the first upload) is in
[android/README.md](android/README.md#one-time-setup). After that, every merge to `main` that touches `android/`
uploads a new version to the internal testing track.

### 3. The phone
1. Install ProMinder from the Play Store (internal testing), sign in with Google.
2. Go through **Setup**: notifications, *Alarms & reminders*, the urgent channel through Do Not Disturb, battery
   optimization off, and Samsung's *never sleeping apps*. Each item opens the right settings screen.
3. **Move your data to this phone** (the first time) or **Restore my data** (a new phone or a reinstall).

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

**⚙** opens the settings (times, limits, Shabbat, how loud). **Tasks** lists today's and all open tasks (✓ / ⏰
on each row), each with its next action. Tap a task to see and edit its description, steps and history.
**Diagnostics** shows the backup, the next reminder check, the tick log, and test buttons for each level.

## Settings

### In the app (⚙)
Times, delays and limits are edited in the app: **⚙ → Settings**. They're stored on the phone (and backed up),
and apply from the next reminder check. Saying it in the chat does the same thing (see
[Say it instead of setting it](#say-it-instead-of-setting-it)). ↺ next to a setting puts it back to its default.

| Group | Settings (defaults) |
|---|---|
| Daily messages | time zone (`Asia/Jerusalem`), morning plan (`07:30`), mid-day check-ins (`13:00, 17:00`), evening review (`21:30`), new chat day starts at (`04:00`) |
| Quiet hours | `23:00` – `07:00`: no proactive messages at all |
| Shabbat & holidays | on; location (Rosh HaAyin), outside Israel (off), candle lighting (`20` min before sunset), havdalah (`0` = nightfall), quiet before / after (`60` / `60` min) |
| Meetings | reminder before (`15` min, plus one at start), first day of the week (Sunday), this week's meetings at (`08:00`), tomorrow's meetings at (`20:00`); either digest can be off |
| Check-ins & limits | follow-up interval (`30` min) and stop after (`4`), minimum gap between messages (`20` min, meetings exempt), messages per day (`20`), check-ins per task per day (`4`), follow-ups the assistant may pick (`10` min – `7` days), a reply refers to the last check-in for (`3` h) |
| How loud | urgent after (`1`) ignored check-in, alarm for (important + critical tasks) after (`2`) ignored check-ins, alarm repeats every (`2` min) |
| App | show which AI model replied (off) |

### On the Lambda (its environment variables)
The keys and the spend guards, which stay on the server so they hold even if the login were ever compromised.
All of them are listed in [`backend/.env.example`](backend/.env.example); to change one: AWS console → Lambda →
`prominder-api` → Configuration → Environment variables.

| Variable | Default | Meaning |
|---|---|---|
| `LLM_CALLS_PER_DAY` / `LLM_PER_MINUTE` | `300` / `30` | spend guards |
| `LLM_TIMEOUT_S` / `LLM_MAX_TOKENS` | `25` / `500` | per LLM call (the phone may ask for a shorter timeout, never a longer one) |
| `LLM_REASONING_EFFORT` | empty | `minimal` / `low` / `medium` / `high` for reasoning models (empty = provider default). Reasoning tokens may count toward `LLM_MAX_TOKENS` |

## Development

The app is built, tested and screenshotted by GitHub Actions on every change
([android/README.md → How it's built](android/README.md#how-its-built)). The backend's tests need no AWS or network:

```bash
cd backend
pip install -r requirements.txt pytest "moto[dynamodb]"
python -m pytest -q                 # moto fakes DynamoDB
python scripts/metrics.py           # success measures for the last 28 days, from the backup (read-only;
                                    #   in CloudShell). --from 2026-10-01 --to 2026-11-01 for a range
```

## Project layout

```
android/             the app (Kotlin, Jetpack Compose); its layout is in android/README.md
backend/app/
  lambda_app.py      the Lambda's routes, and the entry point of the daily alive check
  auth.py            Google sign-in → the app's own login token; the owner check on every request
  sync.py            the phone's backup: restore (and the handover), upload; the stored rows
  alive.py           "is the phone still alive?": an email if it hasn't backed up for 2 days
  llm_proxy.py       POST /llm (the key, the daily cap, the rate limit) and GET /prompts
  llm.py             OpenRouter client: fallback models, timeout, errors → the phone's templates
  db.py              DynamoDB: the single-table layout, queries, batch writes
  config.py          the function's settings (environment variables)
  prompts/           the assistant's prompts: core + playbooks/, system, checkin, daily_* (the app ships them)
backend/tests/       the Lambda's tests
backend/scripts/     aws_lambda_setup (creates everything on AWS), metrics (success measures)
docs/                the Android plan and what each phase added
.github/workflows/   android.yml (build, test, screenshots, Play upload), backend.yml (test, deploy to Lambda)
```

The web app, the Render server and its Python logic (`web/`, most of `backend/app/`, `db/`, `render.yaml`) were
removed in Android Phase F; they're in the repository's history.
