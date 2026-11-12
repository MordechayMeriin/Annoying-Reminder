# Annoying Reminder → ADHD Assistant: Implementation Brief

Nov 10, 2026 · @Mordechay

## How to use this brief

This brief is the spec for a Claude Code session implementing the change in `MordechayMeriin/Annoying-Reminder`. Before writing code, read the repo's `README.md` (architecture, guardrails, settings) and `backend/app/logic.py`.

- **One phase at a time.** Work in phase order (see Phases). Each phase gets its own branch and PR. Don't start a phase until the previous phase's acceptance checks pass.
- **Conflicts.** Where this brief and the code disagree on a detail, follow the code's conventions and note the deviation in the PR description.
- **Scope.** There is one user, the owner. Do no multi-user work.
- **Settings.** Every new behaviour ships with a default and must work with zero setup.

## The one rule: the app does the work

The user has ADHD. A feature that makes them *maintain the app* gets abandoned and then becomes one more source of guilt. Maintaining the app means filling in fields, sorting lists, configuring options, or answering questions just to keep the system running. **Every feature must pass these six tests, and a PR that fails one should be redesigned, not merged.**

1. **Say it once, in plain words.** The only required input is what the user would say anyway. The assistant infers everything else: the first step, size, priority, stage, recurrence and hard stops. Never ask the user to fill in a field.
2. **One tap or nothing.** Every question the app asks comes with reply buttons. Typing is always optional.
3. **Silence is an answer.** No reply means "accept the default": the proposed plan stands, and an unanswered session counts as paused. Silence is never a failure, and for tasks without a near deadline it never raises the pressure.
4. **Act from the notification.** The two most common actions, starting and finishing, work from the notification without opening the app.
5. **No new chores.** There's no inbox to empty, no list to groom, and no review the system needs to stay healthy. New displays, like the focus card and timer, show state and never demand input.
6. **No extra noise.** New features may *replace* messages, never add to the daily total. Every proactive message goes through `logic.contact_decision` and the existing caps, quiet hours and Shabbat rules.

## What changes

Today the app *reminds*. After this work it helps the user **start, keep going and come back**, with less effort than today, not more. The existing architecture rule gets one extension:

> **Code decides WHEN, WHICH playbook and WHICH buttons. The LLM writes the words.**

The work rests on four pillars:

1. **Stages, not just open or done.** Every task has a stage, and the app's move depends on it (diagram below).
2. **One-tap replies everywhere.** Code attaches reply buttons to every message, and the two most useful buttons also appear on the notification.
3. **Focus sessions.** ▶ Start turns a nudge into a running timer with a check-in at the end.
4. **Playbooks.** `coach.md` splits into a short core plus situational prompts, and code picks which one applies.

Learning from the user's own data comes last, once the basics are in daily use.

&#91;embedded content: task lifecycle · 5 stages, 2 recovery loops\]

| Stage | Enters when | What the app does next | User effort |
| --- | --- | --- | --- |
| Captured | The user says or shares anything to do | Fills in the first step and size in the same turn, without asking | None |
| Clarified | The task has a `next_action` | Becomes a candidate for each morning's Today's 3 | None |
| Planned | It's in Today's 3. The morning proposal stands unless the user changes it | Check-ins show the next action with ▶ Start 5 min | One tap, or ignore |
| In session | The user taps ▶ Start | Timer runs; at the end, one check-in with Done, Another round and Stop | One tap |
| Paused | The user taps Stop during a session, or doesn't answer at session end | Offers an optional "where did you stop?" line, shown next time | Optional |
| Avoided | 3 or more postponed or ignored check-ins in 7 days | Stops nagging; sends one message with reason buttons | One tap, or ignore |
| Done | The user taps ✓ Done anywhere | Counted in the wins strip and the evening message | None |

## Features

Each feature lists what the **user** does, what the **app** does, the **code** it touches, and the **acceptance** checks. File names refer to the current repo.

### F1. Reply buttons everywhere (foundation for the rest)

- **User:** taps a button, or ignores it.
- **App:** every proactive message, and every chat reply about exactly one open task, gets 2–4 reply buttons ("chips"). **Code chooses them from the message kind and task stage, with no LLM.** Chips appear only under the latest assistant message.

| Message | Chips |
| --- | --- |
| Task check-in | ▶ Start 5 min · ✓ Done · Later · I'm stuck |
| Morning plan | ▶ Start the first one · Change it |
| Session end | ✓ Done · ▶ Another round · Stop |
| Avoided task | Too big · Unclear · Boring · Not now |
| Re-entry (F7) | Pick one for me · Move overdue to next week |
| Sunday cleanup (F8) | Drop them · Keep |
| Meeting reminder | none (existing behaviour) |
| Mid-day check-in | ▶ Start next · What now? |
| What now? answer (F4) | ▶ Start · Low energy · Something else |
| Chat reply about one open task | ▶ Start 5 min · ✓ Done |
| Evening review | none (read only) |

- **Action chips run without the LLM.** Start, Done, Later, Another round, Stop, Not now, Drop them, Keep and Move overdue go straight through `actions.apply` and write the usual `[…]` chat event. **Text chips** (I'm stuck, Too big, Unclear, Boring, Change it, Pick one for me) send their label as a user message through the normal agent.
- **"Later" is decided by code,** so the user never picks a time. Before 18:00 it means +2 hours, otherwise the next morning. It logs `postponed`.
- **Notification actions.** Task check-ins carry ▶ Start 5 min and ✓ Done as notification actions. These replace today's Done and Snooze. Chrome on Android shows at most two; check `Notification.maxActions`. Pushover has no buttons, but its link already opens the chat at that message, where the chips are.
- **Chip language follows the user.** If the user's latest chat message (or, failing that, the task title) contains Hebrew letters, chips use Hebrew labels; otherwise English. This is a pure function with a label table.
- **Taps count as activity.** Today `thread.last_user_reply_at()` and `user_replied_since()` only count typed chat (`role='user' and kind='chat'`). Change both to also count `kind='event'`, which is what taps write. Otherwise a user who answers only with taps looks like they're ignoring the app and gets escalated. This is a bug under the new design and must be fixed in Phase 1.
- **Code:** `logic.chips_for(kind, stage, lang) -> list[Chip]` (pure, tested); a `messages.chips jsonb` column set when the message is saved; `/messages` returns it; `web/app.js` renders chips under the latest assistant message; `/actions` and the `sw.js` actions accept the new action names with the existing signed tokens; `thread.py` activity queries.
- **Acceptance:** every proactive message has chips. Tapping ✓ Done on a notification makes no LLM call. A user who only taps is never counted as ignoring. Chips disappear once the user replies.

### F2. Clarify silently

- **User:** nothing.
- **App:** when a task is added, the same `add_task` call fills `next_action` (a verb-first physical step of 15 minutes or less) and `estimate_min`. There's no question and no confirmation. The confirmation line shows it, for example "Saved: Taxes. First step: find last year's PDF in email." Over 45 minutes, the next action is the first 15-minute chunk. Subtasks are created only when the user asks, says they're stuck, or taps Too big. If the user corrects it ("no, first I need to call the accountant"), the agent updates `next_action`.
- **Everywhere a task appears, the next action leads.** That covers the push body, check-in context, focus card and template fallback.
- **Existing tasks:** a one-off `scripts/backfill_next_actions.py` makes one LLM call per open task.
- **Code:** migration; `tools.py` (`add_task` / `update_task` params, `next_action` required in the schema description); `system.md`; `coach.template_line` and `push_title`; `context.format_task`.
- **Acceptance:** every task added through chat has a next action. Push bodies show it, including when the LLM is down.

### F3. Today's 3: proposed by the app, accepted by silence

- **User:** reads the morning message. Optionally taps Change it.
- **App:** the morning message proposes up to three focus tasks, listing meetings separately, with the first step of task 1. The LLM picks them by calling `set_focus(task_ids)` inside the morning compose. If the LLM fails, code picks: overdue, then due today, then by priority. **They're saved as focus right away (`focus_date = today`), with no reply needed.** Change it opens the normal chat ("swap 2 for the dentist").
- **The focus card replaces today's `#plan` preview** at the top of the chat. It shows three rows, each with the title, next action and ▶ Start. Done rows tick off, and there's a What now? button (F4). It's display only, with nothing to configure.
- **Noise drops.** During the day, check-ins go only to focus tasks, tasks with a deadline in the next 48 hours, requested reminders and meetings. Undated non-focus tasks aren't nudged; they compete for tomorrow's focus instead. Mid-day check-ins are skipped when the user was active in the last 60 minutes or a session ran in the last hour.
- **Code:** migration (`focus_date`); `tools.py` (`set_focus`); `daily_morning.md`; `coach.compose_daily` (today it calls chat\_completion with no tools, so switch the morning job to llm\_loop.run with the set\_focus and list\_tasks subset); `tasks.due_for_contact` (the focus filter); `web/` (focus card).
- **Acceptance:** focus is set every morning even with no reply. Undated non-focus tasks get zero check-ins. The fallback picks focus when the LLM is down.

### F4. "What now?": the decision is made for the user

- **User:** taps What now? on the focus card or a chip. Optionally taps Low energy.
- **App:** code picks **one** task in under a second, with no LLM. It takes the first focus task not yet done. Then it checks whether the task's estimate (times the personal multiplier, default 1.5 until Phase 4 learns it) fits before the next meeting or fence. If not, it picks the shortest focus task that fits; if none fits, it offers a 5-minute start of the first. The reply is "Do this: {next\_action} ({estimate} min). ▶ Start". **Low energy** picks the smallest task with an estimate of 15 minutes or less, or offers 5 minutes of the top one. Something else gives the next candidate. With no open focus tasks, the candidates are, in order: tasks due soonest, then highest priority, then oldest. When all focus tasks are done, the reply says so in a few words and offers one bonus task.
- **Code:** `logic.pick_next(tasks, now, next_fixed_event, multiplier, low_energy)` (pure, tested); `/what-now` endpoint; chat event plus a reply message.
- **Acceptance:** the reply arrives in under a second, makes no LLM call, never picks something longer than the time to the next meeting, and always returns something to do.

### F5. Focus sessions

- **User:** taps ▶ Start, then optionally ✓ Done or Stop.
- **App:** a session starts: 5 minutes from a notification, 25 from the app. On the start screen, one tap switches between 5, 15, 25 and 45; durations are multiples of 5 because ticks run every 5 minutes. The screen shows a large ring timer, the next action, the resume note (F6), and buttons for ✓ Done, Stop and +5.
- **Templates, not the LLM:** the start message is "▶ 25 min: {next\_action}". At the end, a check-in arrives at the first tick after the end time, with the chips ✓ Done, ▶ Another round and Stop. This costs nothing and never fails. If the app is open, the ring timer also ends on time with a sound; the server check-in covers the case where it isn't. Another round starts a new session of the same length. Stop records the outcome as stopped and offers the breadcrumb field (F6).
- **Silence at the end:** after 15 minutes with no answer, the session is recorded as `paused`, nothing more is sent, and the task stays in focus.
- **Focus mode while a session runs:** other check-ins are held until the session ends plus 10 minutes. Meetings and fences (F9) still come through.
- **Exemptions:** session messages are user-requested, so they're exempt from the minimum gap and the per-task cap, like requested reminders. They still count toward the daily total. Add every new message kind (session, reentry) to thread.PROACTIVE\_KINDS so the caps see it.
- **Records:** planned versus actual minutes. Actual runs until the Done or Stop tap; with silence, it equals the planned time.
- **Code:** `sessions` table; `POST /tasks/{id}/start`, `POST /sessions/{id}/end`; session-end handling in `scheduler.plan_tick`; focus hold in `logic.contact_decision`; `sw.js` (the start action opens the session screen); a session view in `web/`.
- **Acceptance:** one tap on a notification starts a 5-minute session. The end check-in arrives within 5 minutes of the end. No other check-in arrives during a session except meetings and fences.

### F6. Breadcrumbs: optional, never blocking

- **User:** optionally types or dictates one line.
- **App:** after Stop or an unanswered session end (only when a session actually ran on that task), the chat shows a small optional field, "Where did you stop? (optional)", with a mic. Doing nothing skips it. The agent can also save a breadcrumb from chat ("I stopped at page 3"). The note is saved as `resume_note`. It's shown on the next session screen and used by the next check-in ("You stopped at row 40. Next is column F."). It's cleared on Done.
- **Code:** migration; `actions.py`; `update_task` param; `context.format_task`; `web/`.
- **Acceptance:** it's never a modal, and ignoring it changes nothing.

### F7. Gentler pressure: avoidance, de-escalation, re-entry

Today, the more a user avoids or goes quiet, the louder the app gets. For ADHD that's backwards: pressure on an avoided task feeds shame, and shame feeds avoidance.

- **Avoided stage (code).** `logic.task_stage()` marks a task *avoided* after 3 or more `postponed` or `ignored` events in 7 days. Ordinary check-ins about it stop, and the user gets **one** message using the avoiding playbook with the chips Too big, Unclear, Boring and Not now. A tap logs `avoid_reason`, and the agent applies the matching move (playbook table below). For example, Too big proposes three steps and offers ▶ 5 min on the first. **Not now** parks the task for 7 days with no check-ins. Silence means one more try in 3 days, then it's parked. After a park, only events since the park count toward the avoided threshold.
- **De-escalation.** The urgent and alarm levels in `logic.alert_level` apply only to meetings, fences, reminders the user asked for, and tasks with a deadline in the next 48 hours. Everything else stays at normal level with at most one check-in a day.
- **Re-entry after a lapse.** No activity (chat *or* taps, per F1) for 48 hours, not counting Shabbat and holidays, stops task check-ins until the next chat or tap. The next morning message becomes a re-entry message: no counts, no overdue list, "Good to see you. Want me to pick one thing for today?", with the chips Pick one for me and Move overdue to next week. Meeting reminders continue as normal.
- **Code:** `logic.task_stage`, `alert_level`, a lapse check; `scheduler.py`; `log_interaction(avoid_reason)`; `actions.py` (`park`, `move_overdue`); playbooks.
- **Acceptance:** a scripted test of 3 silent days produces exactly one re-entry morning message and no urgent or alarm pushes for undated tasks. An avoided task gets one reason message, not repeated nags.

### F8. Sunday cleanup, inside an existing message

There's no separate weekly review ritual, because a ritual is a chore.

- **User:** one tap, or nothing.
- **App:** Sunday's morning message absorbs the existing week-meetings digest, so it goes from two messages to one. It adds one line of wins ("You finished 9 things last week") and up to 3 stale tasks (open 21+ days, no progress events), with the chips Drop them and Keep. Silence means keep, and the same tasks aren't offered again for 4 weeks.
- **Code:** `coach.compose_daily` (Sunday variant); `meeting_digest` merged in when its time falls within an hour of the morning plan; `actions.py` (bulk cancel with events).
- **Acceptance:** Sunday has one fewer message than today. Drop them cancels exactly the listed tasks.

### F9. Hyperfocus fences, learned from chat

- **User:** mentions a fixed hard stop in passing, for example "I pick up the kids at 17:00 every weekday".
- **App:** the agent saves it as `kind='fence'` with a recurrence. Fences always send at urgent level, come through sessions and focus mode, and never come through quiet hours or Shabbat. They appear in the task list and can be cancelled like any task. There's no settings screen for them.
- **Code:** `kind` enum; `add_task` accepts `fence`; `logic.contact_decision` (fences are exempt from the gap and caps, like meetings); `alert_level`.
- **Acceptance:** a fence fires during a running session.

### F10. Capture shortcuts

- **User:** shares text from any app, taps the mic, or uses the home-screen shortcut.
- **App:** a PWA `share_target` in `manifest.webmanifest` posts the shared text into the chat as a user message, where the normal agent saves it (F2 fills the rest). There's no inbox. A mic button in the chat input uses the Web Speech API, with Hebrew and English chosen by the same rule as the chips. A manifest `shortcuts` entry, "New task", opens the chat with the input focused.
- **Acceptance:** text shared from WhatsApp becomes a saved task with a next action, and the user types nothing.

### F11. Visible wins

- **App:** the focus card shows "Done today: n", and finished rows stay visible with a tick. The evening message leads with wins, and finished subtasks and sessions count too. There are no streaks, since a broken streak is a shame trigger.
- **Code:** `web/` focus card; `daily_evening.md`.

### F12. Settings and preferences from chat

- **User:** says it, for example "don't message me after 22:00" or "shorter messages please".
- **App:** a tool `update_setting(key, value)` changes any key in `config.SPECS`, validated by the existing settings code. It applies immediately and says so in one line ("Done: quiet hours now start at 22:00. Say 'undo' to revert."). There's no confirmation step, because the change is reversible. Preferences that aren't settings (tone, length, topics to avoid) go through `remember_preference(text)`. They're stored and injected into every prompt, and listed read-only under ⚙ so the user can delete one.
- **Code:** `tools.py`; `user_settings.py`; `preferences` table; `prompting.render` (`{preferences}` slot).
- **Acceptance:** "stop messaging me after 10pm" changes `quiet_start` with no screen visited.

## Effort audit

Every moment where the user meets the app, before and after. The total effort goes **down**, even though the app does much more.

| Moment | Today | After | User effort after |
| --- | --- | --- | --- |
| Adding a task | Type it in chat | Type, share or dictate; first step filled in automatically | Say it once |
| Morning | Read the plan | Read the plan; Today's 3 is already set | None (Change it optional) |
| A check-in arrives | Open the app, type a reply, or Done/Snooze | ▶ Start 5 min or ✓ Done on the notification | One tap, or ignore |
| Deciding what to do | Scan the list and choose | What now? | One tap |
| Starting | Up to the user | Timer starts from the tap | None |
| Interrupted | Thread lost | Optional one line, or nothing | Optional |
| Avoiding a task | Escalating check-ins and alarms | One message, four reason buttons, then quiet | One tap, or ignore |
| Away for days | A pile of alarms | One kind message on return | One tap, or ignore |
| Weekly upkeep | None (lists rot) | One cleanup line on Sunday, inside an existing message | One tap, or ignore |
| Changing behaviour | Open ⚙ and find the setting | Say it in chat | Say it once |
| Daily messages | Morning, 2 mid-day, evening, digests | Fewer (Sunday merged, undated non-focus tasks silent, mid-day check-ins skipped when active) | Fewer interruptions |

### Cut from the earlier draft, and why

| Idea | Why it's out |
| --- | --- |
| Inbox with daily triage | A daily confirmation chore. Replaced by silent clarification at capture (F2) |
| Interactive weekly review | A ritual the system depends on. Replaced by one optional tap on Sunday (F8) |
| Time blocks on the calendar | Asks the user to plan their calendar. Replaced by What now? (F4), which decides at the moment of doing |
| Leave-by alarms with travel time | Needs data entry per meeting. Can return later if travel time can be inferred |
| Midpoint pings during sessions | Interrupts the focus the session exists to protect |
| Overrun check | Extra noise. The end check-in plus fences cover hyperfocus |
| Fences configured in settings | Setup work. Learned from chat instead (F9) |
| Editable learned profile | Maintenance. The profile is read-only, with "forget this" per stated preference |
| Asking the user for estimates | A form field in disguise. Estimates are inferred, then corrected by real session times |

## Playbooks

Today every prompt includes all of `coach.md`, so a mid-size free model reads advice for every situation on every call. The fix is a short core, always included, plus at most two situational playbooks chosen by code. Smaller prompts get better behaviour from cheaper models.

```
backend/app/prompts/
  core.md            # always: identity + the communication rules below (~20 lines)
  playbooks/
    planning.md      # morning: Today's 3, first step of #1, meetings listed
    stuck.md         # one focused question OR one 10-minute first step
    avoiding.md      # reason-matched move (table below)
    overwhelmed.md   # shrink to one thing, hide the rest, permission to drop
    reentry.md       # after a lapse: no counts, no catch-up, offer to pick one thing
    celebrate.md     # progress: a few words of acknowledgement, then back off
    sunday.md        # wins line + stale tasks, one question
```

The existing `system.md`, `checkin.md` and `daily_*.md` stay as the task frames, with `{coach}` replaced by `{core}` and `{playbooks}`. Session messages are templates and need no playbook.

### Code chooses: `logic.select_playbooks(...) -> list[str]`

This is a pure, tested function that returns at most two names:

- **Proactive messages:** job type (morning → `planning`, Sunday morning → `planning` + `sunday`, lapse → `reentry`) and task stage (avoided → `avoiding`).
- **Chat turns:** the stages of the likely-topic candidates that `agent.likely_topic` already computes, plus simple signals. A `stuck` event today or a tap on I'm stuck → `stuck`. A tap on Too big, Unclear or Boring → `avoiding`. At 15:00 or later with 3 or more focus tasks open and none done → `overwhelmed`. A task completed this turn → `celebrate`.
- **No signal:** core only. `core.md` has one line per playbook, so the model can still adapt.

### core.md: the communication rules

- Name one concrete next action, never the goal: "Open the form, fill in page 1", not "work on taxes".
- **Never ask for something the app can infer.** Guess, act, and let the user correct you.
- Ask at most one question, and **only one the chips can answer**. The prompt gets the chip labels for this message, so the question and the buttons match.
- Describe behaviour, never character. No guilt, no lectures, no counting failures back at the user.
- Be exact about time ("at 17:00", not "later"). Repeat a commitment back once.
- Acknowledge progress in a few words, then back off.
- At most three sentences, except in the morning plan. Reply in the language the user last wrote in.

### avoiding.md: match the move to the reason

| Reason (chip) | Move |
| --- | --- |
| Too big | Split into 3 steps of 10–30 minutes; offer ▶ 5 min on the first |
| Unclear | Name the one question that would unblock it, and who could answer it; offer to draft that message |
| Boring | Offer a 5-minute session and suggest pairing it with something pleasant |
| Scary (agent-detected) | Shrink to a step smaller than the fear: "just write the subject line" |
| People (agent-detected) | Draft the message for them; ask only for approval |
| Not now | No message. Code parks the task for 7 days |

The four chips are deliberately the easiest to tell apart. The agent can still record `scary`, `people` or `no_energy` from what the user writes.

The same `core.md` and playbooks can later be packaged as a Claude skill (`SKILL.md` plus `playbooks/`), so the coaching voice also works in a normal Claude chat. That's optional and not part of any phase.

## Data model, tools and modules

Everything is additive and idempotent, in the style of `db/migrations/`. Update `db/schema.sql` to the full new state as well. There are no new services and nothing that affects the free hosting.

### Migration `006_adhd_assistant.sql` (Phases 1–3)

```sql
alter table tasks add column if not exists next_action      text;         -- F2
alter table tasks add column if not exists estimate_min     integer;      -- F2
alter table tasks add column if not exists focus_date       date;         -- F3
alter table tasks add column if not exists resume_note      text;         -- F6
alter table tasks add column if not exists parked_until     timestamptz;  -- F7 "Not now"
alter table tasks add column if not exists stale_offered_at timestamptz;  -- F8 cooldown
-- kind check: + 'fence' (F9). Drop and re-add the constraint, as earlier migrations do.

alter table task_events add column if not exists avoid_reason text
  check (avoid_reason in ('too_big','unclear','boring','scary','people','no_energy'));
-- kind check: + 'session_started', 'session_ended', 'paused', 'parked'

alter table messages add column if not exists chips jsonb not null default '[]';  -- F1
-- kind check: + 'session' (session start/end messages), 'reentry'

create table if not exists sessions (                                          -- F5
  id          bigserial primary key,
  task_id     bigint not null references tasks (id) on delete cascade,
  started_at  timestamptz not null default now(),
  planned_min integer not null check (planned_min % 5 = 0),
  ended_at    timestamptz,
  outcome     text check (outcome in ('done','another','stopped','paused'))
);
create index if not exists sessions_open_idx on sessions (started_at) where ended_at is null;

create table if not exists preferences (                                       -- F12
  id bigserial primary key, text text not null, created_at timestamptz not null default now()
);
-- RLS on + grants revoked for the new tables, as in 002.
-- New tables get a nullable user_id (unused) to keep a later multi-user step cheap.
```

### Tools (`tools.py`)

| Tool | Change |
| --- | --- |
| `add_task` / `update_task` | + `next_action`, `estimate_min`, `resume_note`; `kind` + `fence` |
| `log_interaction` | + optional `avoid_reason` |
| `set_focus` | New: Today's 3 (morning compose and Change it) |
| `update_setting` | New: any key in `config.SPECS`, through the existing validation |
| `remember_preference` | New: free-text preference, injected into prompts |

That makes 12 tools. Pass a subset per context, as `llm_loop.run` already allows: the morning compose gets `set_focus` and `list_tasks`, check-ins get `schedule_followup`, and chat gets the full set.

### Modules

| File | Change |
| --- | --- |
| `logic.py` | `chips_for`, `task_stage`, `select_playbooks`, `pick_next`, `later_time`, lapse detection, focus and session hold and fence exemption in `contact_decision`, de-escalation in `alert_level`. All pure, all unit-tested |
| `thread.py` | Activity includes `kind='event'`; save `chips` |
| `scheduler.py` | Session end and silence handling; avoided-stage single message; re-entry morning; Sunday merge |
| `coach.py` | `{core}` + `{playbooks}` + `{preferences}` + chip labels in prompts; `next_action` in templates and push body; focus fallback picker |
| `agent.py` | Candidate stages and recent signals → playbooks; tool subset |
| `tasks.py` | Focus filter in `due_for_contact`; parked and stale queries |
| `actions.py` | `start`, `later`, `another`, `stop`, `park`, `drop`, `keep`, `move_overdue`, all writing chat events |
| `main.py` | `/what-now`, `/tasks/{id}/start`, `/sessions/{id}/end`, `/messages` returns chips, `/actions` new names |
| `notify.py` / `sw.js` | Notification actions ▶ Start 5 min and ✓ Done; start opens the session screen |
| `web/` | Chips, focus card, What now?, session screen with ring timer, breadcrumb field, mic, share target, shortcut, wins |
| `prompts/` | `core.md`, `playbooks/*.md`; `coach.md` removed |

### LLM cost

Session messages, What now?, all action chips and the re-entry fallback make **no** LLM calls. The new LLM work is `set_focus` inside the existing morning call and the one-off backfill script. Smaller prompts reduce tokens per call. Expected daily calls stay at or below today's level, well inside `LLM_CALLS_PER_DAY=300` and the free tier.

## Phases

One branch and PR per phase, in this order. Each phase is usable on its own. **Every PR must:**

- pass `python -m pytest -q` with new tests for every new pure function in `logic.py`;
- add at least one scripted-LLM end-to-end test in `tests/` using the existing fakes;
- update `scripts/simulate_tick.py` to show the new flow (and still work with `--llm-down`);
- include the migration, `schema.sql` and README changes.

At the end of each phase, list the owner's phone check in the PR description. That's what the owner verifies by hand after deploying.

1. **Phase 0: Baseline** (small)
   - `scripts/metrics.py`: prints the success measures (see the end of this brief) for a date range, from the existing tables. Run it once before Phase 1 and save the output in the PR.
2. **Phase 1: One tap and a clear first step** (F1, F2, F3, playbooks)
   - Chips with notification actions, taps counted as activity, silent `next_action`, Today's 3 with silence-accepts, the focus card, the focus-only check-in filter, `core.md` and playbooks with `select_playbooks`, and the backfill script.
   - *Tests:* `chips_for` per kind, stage and language; `select_playbooks`; `due_for_contact` focus filter; a tap resets the ignored streak; morning with the LLM sets focus; morning with the LLM down uses the code picker.
   - *Phone check:* a check-in arrives with ▶ Start 5 min and ✓ Done. Done works from the notification. A new task shows its first step. The morning focus card shows three tasks without a reply.
3. **Phase 2: Start and come back** (F4, F5, F6, F10, F11)
   - What now?, sessions with timer and end check-in, focus hold, breadcrumbs, share target, mic, home-screen shortcut, and the wins strip.
   - *Tests:* `pick_next` (fits before a meeting, low energy, nothing fits → 5-minute start); session start → tick after end → end check-in → silence → `paused`; no check-in during a session; a meeting still arrives during a session.
   - *Phone check:* one tap on a notification opens a running 5-minute timer. The end check-in arrives. Text shared from another app becomes a task.
4. **Phase 3: Gentler pressure** (F7, F8, F9, F12)
   - Avoided stage and its single message, reason moves, parking, de-escalation, re-entry, the Sunday merge and cleanup, fences, `update_setting`, `remember_preference`.
   - *Tests:* three silent days give exactly one re-entry message and no urgent pushes for undated tasks; an avoided task gets one message, then parks; `alert_level` matrix; Sunday has one fewer message; a fence fires during a session; "no messages after 10pm" changes `quiet_start`.
   - *Phone check:* tap Too big on an avoided task and get three steps with ▶ 5 min. Say a preference in chat and see it apply.
5. **Phase 4: Learn from use** (after 3–4 weeks of Phase 3 in daily use)
   - **Personal multiplier:** the median of actual ÷ estimated time from `sessions`, used in `pick_next` and in morning estimates.
   - **Response hours:** the share of proactive messages answered within 60 minutes, per hour. Check-ins due in hours with almost no response move to the next good hour, within all guardrails. Learning can only make the app quieter or better-timed, never louder.
   - **Profile:** a short read-only text under ⚙ ("Responds best 09–12 · Takes about 1.6x estimates · Usually avoids for: unclear"), plus stated preferences, each with a delete button. Injected into prompts as `{profile}`.
   - Computed weekly by a job into a small `insights` table, with plain SQL and no LLM.

## Constraints

**Keep:**

- "Code decides when". Every proactive message goes through `logic.contact_decision`.
- A template fallback for every LLM-written message.
- Quiet hours and Shabbat are never overruled, fences included.
- The single-owner security model (`OWNER_USER_ID`, RLS with no policies, signed action tokens).
- The free-tier hosting and the spend caps.
- Hebrew and English throughout, including chips and the mic.

**Don't add:**

- required settings, onboarding, modals or new screens that need input;
- new third-party services;
- anything that raises the number of daily messages.

## Decisions already made

These are **constants in `logic.py`, not settings**, to keep ⚙ from growing. Change them in code if real use shows a need.

| Topic | Decision |
| --- | --- |
| Audience | The owner only. A commercial version would be considered only if the success measures below move clearly |
| Avoided stage | 3+ postponed or ignored events in 7 days |
| Not now | Parks the task for 7 days |
| Lapse | 48 hours with no chat or tap, not counting Shabbat and holidays |
| Stale (Sunday cleanup) | Open 21+ days with no progress; offered again after 4 weeks at the earliest |
| Later | +2 hours before 18:00, otherwise the next morning |
| Sessions | 5 minutes from a notification, 25 from the app; choices 5, 15, 25, 45; no midpoint ping; silence 15 minutes after the end → paused |
| Alerts | Urgent or alarm only for meetings, fences, requested reminders and deadlines within 48 hours |
| Playbook language | English (the model replies in the user's language). Chip labels have Hebrew and English tables |
| Name | Unchanged for now. Renaming is out of scope |

## Success measures

Phase 0 computes the baseline from existing data. Compare it with the four weeks after Phase 2. If most measures move clearly, the owner will consider a commercial version.

| Measure | Computed from | A clear win looks like |
| --- | --- | --- |
| Nudge → action time | Proactive message → next done, progress or session start on that task | Clearly shorter |
| Focus tasks finished | Today's 3 done by evening (before Phase 1: due today and done today) | 2 of 3 or better on most days |
| Ignored check-ins | `ignored` events per week | Clearly fewer |
| Repeatedly postponed tasks | Tasks with 3+ postponements in 7 days | Fewer, and they get unstuck |
| Overdue pile | Open tasks past `due_at` at week end | Shrinking |
| Taps vs typed replies | `event` vs `chat` user messages | Most interactions are taps (a proxy for low effort) |
| Owner's rating | One line in the Sunday message: "Did this help this week?" with chips 1–5 | Consistently 4–5 |
