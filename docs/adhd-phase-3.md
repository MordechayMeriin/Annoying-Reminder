# ADHD assistant: Phase 3 (gentler pressure)

Implements Phase 3 of [the implementation brief](<../Annoying Reminder → ADHD Assistant Implementation Brief.md>):
F7 avoidance, de-escalation and re-entry, F8 the Sunday merge and cleanup, F9 hyperfocus fences and
F12 settings and preferences from chat. This file is written to be pasted into the pull request description.

## Deploy steps

1. Supabase → SQL Editor → run [`db/migrations/008_gentler_pressure.sql`](../db/migrations/008_gentler_pressure.sql)
   (idempotent, additive: three columns on `tasks` and one more `task_events` kind; the deployed code
   keeps working against it).
2. Deploy both Render services and reopen the app once (new `app.js`).
3. Expect it to be quieter from the first day: tasks without a near deadline get one normal check-in a
   day, and the Sunday 08:00 "this week's meetings" message is now part of Sunday's morning plan.

## What changed

- **Avoided stage (F7).** `logic.avoid_move`: a task with 3+ postponed / ignored events in 7 days gets no
  more ordinary check-ins. `scheduler.plan_tick` sends **one** reason message instead (the avoiding
  playbook, or the template "Taxes keeps slipping, and that's fine. What's in the way?") with
  Too big · Unclear · Boring · Not now, always at normal level and alone in its message. Too big /
  Unclear / Boring are text chips: `agent.py` records the reason itself (`task_events.avoid_reason`,
  kind `avoided`) before the model runs, and the avoiding playbook applies the move (Too big: three
  steps, the first becomes the next action, ▶ Start 5 min under the reply). The agent can record
  `scary` / `people` / `no_energy` with `log_interaction(avoid_reason)`. **Not now** (`actions.park`,
  no LLM) parks the task for 7 days: no check-ins, out of Today's 3, not offered by What now?.
  Silence: one more try 3 days later, then the task is parked without a message. A reason, a started
  session, progress or a commitment is a fresh start (`tasks.reset_avoidance`): earlier events stop
  counting; after a park only events since the park count.
- **De-escalation (F7).** `logic.escalates`: only meetings, fences, reminders the user asked for and
  tasks with a deadline within 48 hours can be urgent or alarms (`logic.alert_level`). Everything else
  is normal, with one check-in a day at most: the next one is scheduled a day later, and the per-task
  cap in `logic.contact_decision` is 1 for those tasks, so an LLM-chosen follow-up can't get around it.
- **Re-entry (F7).** `logic.lapsed` / `scheduler.is_lapsed`: 48 hours with no chat message and no tap,
  Shabbat and holidays not counted. During a lapse `tasks.due_for_contact` returns only meetings, fences
  and reminders the user asked for; the mid-day check-ins and the evening review are skipped; the next
  morning message is the re-entry message (kind `reentry`: the reentry playbook, or the template "Good
  to see you. Want me to pick one thing for today?") with Pick one for me · Move overdue to next week.
  **Move overdue** (`actions.move_overdue`, no LLM) gives every overdue task the same weekday and time
  next week. Pick one for me is a text chip that brings the reentry playbook into the chat turn.
- **Sunday (F8).** On the first day of the week the morning plan carries the week's meetings when the
  two times are within an hour (`logic.week_meetings_merged`; the separate `week_meetings` job doesn't
  run then), one line of last week's wins, and up to three stale tasks (open 21+ days, nothing moving,
  not offered in the last 4 weeks) with **Drop them · Keep**. `POST /tasks/bulk-action` cancels exactly
  the ids on the chip. Silence keeps them.
- **Fences (F9).** `kind='fence'` through `add_task` (`system.md` tells the agent to save a hard stop
  mentioned in passing, with its recurrence). A fence fires once at its time, from a template
  ("⛔ 17:00 — Pick up the kids. Time to stop."), urgent, through a running session, the focus hold,
  the gap and the caps; a recurring one then moves to its next time, a one-off closes. What now? counts
  fences as fixed events. They show in the task list with ⛔ and can be cancelled like any task.
- **Settings and preferences from chat (F12).** `update_setting(key, value)` takes any key in
  `config.SPECS` and saves through `user_settings.save` (the same validation as ⚙); the result carries
  the old value so the one-line reply can offer "undo". `remember_preference(text)` stores a line in
  `preferences`; `prompting.render` puts them in every prompt (`{preferences}`), and ⚙ lists them
  under "What you asked for" with ✕ (`GET /preferences`, `DELETE /preferences/{id}`).
- 12 tools in chat; check-ins still get `schedule_followup` only and the morning plan `set_focus` +
  `list_tasks`.

## Deviations from the brief (code conventions won, or a detail needed deciding)

1. **Migration 008** instead of more of 006 (already deployed): `tasks.avoid_asked_at`, `avoid_asks`,
   `avoid_reset_at`, and the event kind `avoided` (a reason tap needs an event of its own; reusing
   `stuck` would have pulled in the stuck playbook next to the avoiding one).
2. **"Deadline in the next 48 hours" also covers one missed in the last 48 hours** for loudness: a task
   an hour overdue is still urgent, one a week overdue isn't. Check-ins themselves still go to every
   overdue task (once a day, at normal level).
3. **A task whose deadline is coming up within 48 hours is never treated as avoided**: it keeps its
   ordinary check-ins, so silence can't park something due tomorrow.
4. **The reason message also goes to a task the user asked to be reminded of** ("Later" taps set a
   requested reminder and log `postponed`, so otherwise three Later taps would never lead to it).
   It still has ▶ Start 5 min · ✓ Done on the notification.
5. **"Then it's parked"** is read as: 3 days after the second message, with no answer to either.
6. **During a lapse, reminders the user asked for still arrive** (as well as meetings and fences): they
   set that time themselves. The mid-day check-ins and the evening review are skipped too.
7. **One re-entry message per lapse**; if the silence goes on, again a week later, not every morning.
   Mornings in between are silent.
8. **The Sunday lines are written by code** (wins, stale tasks, the week's meetings), under the LLM's
   plan, so Drop them acts on exactly the tasks named on the screen. `sunday.md` tells the model not to
   write them itself. A stale task that's in that day's Today's 3 isn't offered for dropping.
9. **Stale** = top-level task, created 21+ days ago, no progress / done / committed / session event in
   21 days, no future due date, not blocked, parked or recurring. Offering marks it for 4 weeks
   whatever the answer.
10. **A fence fires once, at its time, with no lead** (the agent is told `due_at` = the moment to stop).
    One held back more than 30 minutes by quiet hours or Shabbat is skipped rather than sent late.
    A fence can't be completed or snoozed, only cancelled or changed in chat.
11. **Move overdue writes no `postponed` events**: it's an amnesty and mustn't push tasks toward
    "avoided". A recurring overdue task moves to its next occurrence instead of +7 days.
12. **Bulk chips use their own endpoint** (`POST /tasks/bulk-action`: move_overdue, drop, keep); Not now
    is an ordinary task action (`not_now`).
13. **`update_setting` value `default`** resets a setting. Preferences: at most 20, 200 characters each;
    deleting is in ⚙ only (the brief's 12 tools have no "forget").
14. **Not in this phase:** the owner's weekly rating line (success measures) and the read-only learned
    profile (Phase 4).

## Tests

`python -m pytest -q`: 325 passed (44 new). New: `test_logic_phase3.py` (`avoid_move`, `escalates`, the
one-a-day cap, fences in `contact_decision` / `plan_contacts`, parking, `lapsed` with Shabbat,
`reentry_due`, `week_meetings_merged`, the new chips and texts), `test_phase3_flow.py` (scripted LLM end
to end: an avoided task gets one reason message and nothing else; silence → second try → parked; Not now
with no LLM call; Too big recorded by code → three steps with ▶ Start 5 min; three silent days → exactly
one re-entry message, no urgent or alarm push except the meeting; coming back ends the lapse; Shabbat
alone isn't a lapse; Move overdue; Sunday is one message with wins, cleanup and meetings; Drop them
cancels exactly the listed tasks, silence keeps them; a fence fires during a running session; a late
fence is skipped; a fence learned from chat; "stop messaging me after 10pm" changes `quiet_start`, undo;
a preference reaches every prompt and can be deleted), plus the `alert_level` matrix in
`test_pushover.py`. Older tests that expected several check-ins a day on undated tasks now give those
tasks a deadline. `scripts/simulate_tick.py` shows the new flow (also with `--llm-down`).
Migration 008 and the new SQL were run against a local Postgres 16 (upgrade from the Phase 2 schema,
008 twice; fresh `schema.sql` + 008), including the avoided flow, parking, fences, the stale query,
Move overdue, the lapse, `update_setting` and preferences through the real `scheduler`, `agent` and
`actions`. The web app was driven in headless Chromium against the API on the in-memory store (reason
buttons, Too big → ▶ Start 5 min, Move overdue, Drop them, a setting from chat, the preferences list and
✕): no console errors.

## Phone check (owner, after deploying)

- [ ] On a task you've ignored three times: one message with **Too big · Unclear · Boring · Not now**,
      at normal volume. Tap **Too big**: three steps, with **▶ Start 5 min**.
- [ ] Tap **Not now** on another: nothing more about it for a week (☑ Tasks shows 💤 parked).
- [ ] Say "don't message me after 22:00": one line back, and ⚙ shows Quiet from 22:00. Say "undo".
- [ ] Say "shorter messages please": it's under ⚙ → What you asked for, and ✕ removes it.
- [ ] Say "I have to leave at 16:30 today": at 16:30 an urgent ⛔ message arrives, also mid-session.
- [ ] Sunday morning: one message, with the week's meetings at the end (no separate 08:00 one).
- [ ] An undated task you ignore stays at the normal sound and comes once a day.
