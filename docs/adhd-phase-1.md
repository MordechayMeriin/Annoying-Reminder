# ADHD assistant: Phase 0 + Phase 1

Implements Phase 0 (baseline metrics) and Phase 1 (F1 reply buttons, F2 silent first step, F3 Today's 3,
playbooks) of [the implementation brief](<../Annoying Reminder → ADHD Assistant Implementation Brief.md>).
This file is written to be pasted into the pull request description.

## Deploy steps

1. **Before deploying**, run the baseline and save its output for the Phase 2 comparison:
   `DATABASE_URL=... python backend/scripts/metrics.py` (the last 28 days; read-only).
2. Supabase → SQL Editor → run [`db/migrations/006_adhd_assistant.sql`](../db/migrations/006_adhd_assistant.sql)
   (idempotent, additive; the currently deployed code keeps working against it).
3. Deploy both Render services (the web app has a new service worker; reopen the app once).
4. Render → api service → Shell → `python scripts/backfill_next_actions.py --dry-run`, then without
   `--dry-run`. One LLM call per open task without a first step.

## What changed

- **Reply buttons (F1).** `logic.chips_for(kind, stage, lang)` picks 2–4 buttons per message, with no
  LLM. They're stored on the message (`messages.chips`), returned by `/messages` and shown under the
  latest assistant message only. Start / Done / Later run through `actions.apply` with no LLM; text
  buttons send their label as a reply. Labels in Hebrew or English (the latest typed message, else the
  task title). Task check-ins carry ▶ Start 5 min / ✓ Done as notification buttons (replacing Done /
  Snooze); the service worker shows the server's labels, capped at `Notification.maxActions`.
- **Taps count as activity.** `thread.last_user_reply_at()` / `user_replied_since()` count `kind='event'`
  too, so a user who only taps is never "ignoring" the app.
- **Silent first step (F2).** `add_task` / `update_task` take `next_action` and `estimate_min`; the
  confirmation line names the first step. The next action leads in the LLM context, the template
  fallback, the push title and the focus card. Existing tasks: `scripts/backfill_next_actions.py`.
- **Today's 3 (F3).** The morning job now runs through `llm_loop.run` with `set_focus` + `list_tasks`.
  The code's pick (overdue → due today → priority) is offered as a suggestion; if the LLM doesn't call
  `set_focus` (or is down), that pick is saved anyway. Silence accepts it. The focus card replaces the
  `#plan` preview. `tasks.due_for_contact` only returns meetings / fences, requested reminders, Today's 3
  and tasks due within 48 h. Mid-day check-ins are skipped when the user was active in the last hour.
- **Playbooks.** `coach.md` → `core.md` + `playbooks/*.md`; `logic.select_playbooks` picks at most two per
  call (morning → planning; avoided task → avoiding; stuck event today / "I'm stuck" → stuck; 15:00+
  with all of Today's 3 open → overwhelmed; a Done tap since the last reply → celebrate).
- **Template text** no longer counts failures back ("reminder #3, still not done" → "still open. One
  small step?").

## Deviations from the brief (code conventions won, or a detail needed deciding)

1. **`tasks.focus_rank`** (new column in 006): "▶ Start the first one" and the focus card need the order
   the morning plan gave; `focus_date` alone loses it.
2. **▶ Start in Phase 1** = a check-in when the N minutes are up (`remind_at`, requested) plus a
   `session_started` event; no `sessions` rows or timer screen yet (Phase 2). Until the session
   exemption lands in Phase 2, that check-in still respects the 20-minute minimum gap, so it can arrive
   later than 5 minutes.
3. **"What now?"** (mid-day button and the focus card) is a text button for now: it asks the agent.
   The code picker (`/what-now`, F4) replaces it in Phase 2.
4. **`next_action` is required in the tool description, not by validation.** A task added without one
   is still saved (never lose what the user said), and the tool result tells the model to add it with
   `update_task`. A validation error would have spent the turn's single retry.
5. **Grouped check-ins:** the buttons act on the message's first (most important) task. Notification
   buttons stay single-task only, as before. Meeting reminders get no buttons (they used to get
   Done / Snooze on the notification).
6. **"A task completed this turn → celebrate"** is read as a Done tap/event since the assistant's last
   message: that's known before the LLM runs.
7. **Sunday and reentry playbooks** exist but aren't selected yet: their facts (stale tasks, lapse
   detection) come with F7/F8 in Phase 3. The avoided *stage* is already detected, but only to pick the
   avoiding playbook; the single reason message and parking are Phase 3.
8. **Migration 006 holds the whole Phase 1–3 schema** (as the brief lays it out), including the
   `sessions` and `preferences` tables that later phases use.
9. **"Overwhelmed"** uses "3 focus tasks still open" (Today's 3 has at most three, so none done) and
   needs no extra query.
10. A morning with no open tasks skips the LLM call (the template lists the meetings).
11. **`scripts/metrics.py`** connects with plain psycopg in a read-only session and needs only
    `DATABASE_URL`, so it runs without the rest of the app's environment. "Focus tasks finished" uses
    Today's 3 on days that had one and "due today" otherwise, so the baseline stays comparable.

## Tests

`python -m pytest -q`: 226 passed. New: `test_logic_adhd.py` (`chips_for` per kind / stage / language,
`select_playbooks`, `chat_signals`, `later_time`, `task_stage`, `pick_focus`, `may_check_in`),
`test_phase1_flow.py` (scripted LLM end to end: chips and notification buttons, Done with no LLM call,
taps reset the ignored streak, the focus filter, morning with the LLM sets focus, morning with the LLM
down uses the code picker, Change it, the mid-day skip, playbooks in the prompts, the API, the backfill),
`test_metrics.py`. Existing tests that expect check-ins on undated tasks now mark them as focus tasks.
`scripts/simulate_tick.py` shows the new flow, with and without `--llm-down`. Migration 006 and the new
SQL were also run against a local Postgres 16 (upgrade from the previous schema, twice; fresh schema).

## Phone check (owner, after deploying)

- [ ] A check-in arrives with **▶ Start 5 min** and **✓ Done** on the notification.
- [ ] **✓ Done** on the notification works without opening the app (the chat shows `[Marked done: …]`).
- [ ] A new task ("I need to do my taxes") shows its first step in the reply.
- [ ] The morning focus card shows up to three tasks without you replying to anything.
- [ ] Buttons under the latest message disappear once you reply or tap.
