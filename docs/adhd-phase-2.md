# ADHD assistant: Phase 2 (start and come back)

Implements Phase 2 of [the implementation brief](<../Annoying Reminder → ADHD Assistant Implementation Brief.md>):
F4 What now?, F5 focus sessions, F6 breadcrumbs, F10 capture shortcuts and F11 visible wins.
This file is written to be pasted into the pull request description.

## Deploy steps

1. Supabase → SQL Editor → run [`db/migrations/007_focus_sessions.sql`](../db/migrations/007_focus_sessions.sql)
   (idempotent, additive: two columns and two indexes on `sessions`; the deployed code keeps working).
2. Deploy both Render services. The web app has a new service worker and manifest: reopen the app
   once. The share target and the "New task" shortcut appear once Chrome refreshes the installed app's
   manifest (that can take a day; removing and re-adding it to the home screen does it at once).
3. Four weeks after this deploy, compare `python backend/scripts/metrics.py` with the Phase 0 baseline
   (sessions already count as an action in "Nudge → action time").

## What changed

- **What now? (F4).** `logic.pick_next` (pure): the first of Today's 3 not done if its estimate × 1.5
  fits before the next meeting, else the shortest focus task that fits, else 5 minutes of the first.
  Low energy: the smallest task estimated at ≤ 15 min, else 5 minutes of the top one. Something else:
  the next candidate (wraps around, so it always returns something). No focus tasks: due soonest, then
  priority, then oldest. All of Today's 3 done: says so and offers one bonus task. `POST /what-now`
  writes the tap's event (`[What now?]`) and the answer ("Do this: {next action} ({n} min).") with
  ▶ Start · Low energy · Something else. No LLM. The focus card's What now? and the mid-day chip use it.
- **Focus sessions (F5).** `sessions.py` (storage) + lifecycle in `actions.py`: ▶ Start anywhere
  (notification, chip, focus card, What now?) opens a session; `POST /tasks/{id}/start`,
  `POST /sessions/{id}/end` (done / another / stopped), `PATCH /sessions/{id}` (5/15/25/45, +5).
  `scheduler.plan_sessions` (every tick): silence → paused, then the end check-in for sessions whose time
  is up, through `logic.contact_decision` (kind `session`: skips the gap and the per-task cap, not quiet
  hours, Shabbat or the daily cap). The check-in is a template with ✓ Done · ▶ Another round · Stop, and
  ✓ Done · ▶ Another round on the notification. **Focus hold:** `ContactState.focus_hold_until` (session
  end + 10 min) holds every other check-in; meetings (and fences, Phase 3) come through.
- **Session screen (web).** A full-screen ring timer with the next action, the resume note, the length
  pills, ✓ Done · +5 · Stop, and at the end ✓ Done · ▶ Another round · Stop with a sound. ✕ hides it; a
  bar in the header keeps counting. ▶ Start / ▶ Another round on a notification start the session and
  open the timer (`sw.js` → `/?session=1`).
- **Breadcrumbs (F6).** `tasks.resume_note`: after Stop or an unanswered end, an optional
  "Where did you stop? (optional)" field (with a mic) at the end of the chat; ✕ or doing nothing skips
  it. `POST /sessions/{id}/note`; the agent can also save it (`update_task resume_note`). It's in the
  check-in context ("Where they stopped last time"), the template fallback and the next session screen,
  and cleared on Done.
- **Capture (F10).** Manifest `share_target` (GET to `/`): shared text is sent to the chat as
  "Shared with the app: …" and the agent saves it (`system.md` says so). 🎤 in the chat box (Web Speech
  API, `he-IL` / `en-US` by the chips' rule). Manifest shortcut "New task" → `/?new=1` (input focused).
- **Wins (F11).** `GET /focus` returns `wins` (finished tasks and steps since the day reset, sessions and
  minutes); the card shows "Done today: 4 · ⏱ 2 sessions". The evening review's facts and its template
  lead with wins, steps and sessions included.
- **Mid-day check-in** is also skipped when a session ran in the last hour (`logic.session_last_active`).

## Deviations from the brief (code conventions won, or a detail needed deciding)

1. **Migration 007** instead of more of 006 (already deployed): `sessions.end_sent_at` (claimed before
   sending, so overlapping ticks can't send twice; silence counts from it) and `sessions.note_closed_at`.
2. **The start "message" is the chat event** `[▶ 25 min: {next action}]`, not an assistant message or a
   push: the user just tapped, so it doesn't need to count toward the daily total or start the gap.
3. **What now?, Low energy and Something else are action chips** (`action: "what_now"`, no task): the
   brief lists them as neither action nor text chips, and F4 says no LLM. Something else carries the
   already-offered ids (`exclude`) on the chip, so the picker stays stateless.
4. **Estimates.** A task with no `estimate_min` counts as 15 minutes (its next action is ≤ 15 min).
   "Fits" uses estimate × 1.5; Low energy compares the raw estimate with 15, as the brief words it. The
   session length is the shortest of 5/15/25/45 covering estimate × 1.5, capped by the time to the meeting.
   A 5-minute start is still offered when a meeting is less than 5 minutes away ("always returns something").
5. **Next fixed event = meetings only** for now; fences join when they exist (Phase 3).
6. **End check-in level:** urgent, like a reminder the user asked for (a timer should be heard); never
   an alarm. Notification buttons: ✓ Done and ▶ Another round (Chrome shows two); Stop is in the chat.
7. **An end inside quiet hours, Shabbat or past the daily cap isn't sent**; silence then counts from the
   planned end, so the session becomes paused 15 minutes later with nothing sent.
8. **The task's own check-in inside the session (+ hold) is dropped** when a session starts; later ones
   (e.g. "remind me at 5") stay. After Stop or silence there are no more nags about it today: it stays in
   Today's 3 and comes back through What now?, the mid-day check-in and tomorrow's plan.
9. **One session at a time:** starting another closes the open one (stopped if running, paused if
   its time was up). Completing or cancelling a task (button, notification, chat) closes its session.
10. **Actual minutes** run until the tap for Another round too, not only Done / Stop. A tap after the
    session went silent finds no open session, so actual time stays bounded.
11. **The breadcrumb field** is offered for 3 hours after the stop / silence (`BREADCRUMB_HOURS`), and
    closed by saving, ✕, or a resume note saved from the chat. Saving writes `[Where I stopped: …]`.
12. **+5 after the end check-in** clears `end_sent_at`, so the check-in comes again at the new end.
13. **"Done today"** counts every task and step finished today (from `done` events, so recurring tasks
    count), not only Today's 3; zero counts aren't shown. `doneToday` keeps its name in `/focus`.
14. **Dictation fills the box**, it doesn't send: the user can check the text first (one tap on ➤).
15. **Layout fix:** the focus card was pinned at `top: 57px`, so it slid under the header on phones
    where the header buttons wrap. The header's real height is now a CSS variable; the session bar is a
    row inside the header.

## Tests

`python -m pytest -q`: 281 passed (55 new). New: `test_logic_phase2.py` (`pick_next`: fits before a
meeting, shortest that fits, nothing fits → 5-minute start, low energy, something else, no focus, bonus,
all done; session length / state / silence / close / actual minutes; the focus hold and the session
exemptions in `contact_decision`; breadcrumb window; texts in both languages), `test_phase2_flow.py`
(scripted LLM end to end: one tap on a notification → a 5-minute session; tick after the end → the end
check-in → silence → paused with nothing more sent; no check-in during a session while a meeting comes
through; Done / Another round / Stop; the breadcrumb from the field and from chat, in the next check-in
and template; quiet hours; the mid-day skip; What now? with no LLM in under a second, Something else,
Low energy, Hebrew, all done; wins on the card and in the evening). Two Phase 1 tests now expect a
session instead of a plain follow-up. `scripts/simulate_tick.py` shows What now?, a session from a
notification, its end check-in and silence → paused (also with `--llm-down`).
Migration 007 and the new SQL were run against a local Postgres 16 (upgrade from the Phase 1 schema,
007 twice; fresh `schema.sql` + 007), including the whole session lifecycle through `actions` and
`scheduler`. The web app was driven in headless Chromium against the API on the in-memory store
(What now?, Something else, ▶ Start → timer, length switch, hide → header bar, Stop → breadcrumb,
time's up → Another round, share target): no console errors.

## Phone check (owner, after deploying)

- [ ] A check-in's **▶ Start 5 min** on the notification opens a running 5-minute timer.
- [ ] Within 5 minutes after it ends, the end check-in arrives (✓ Done / ▶ Another round on it).
- [ ] Ignore one end check-in: nothing more arrives about it.
- [ ] **What now?** on the focus card answers at once with one thing to do.
- [ ] Tap **Stop** in a session: "Where did you stop?" appears; type or dictate a line, and the next
      timer on that task shows it.
- [ ] Share a WhatsApp message to *Reminder*: it becomes a task with a first step, and you type nothing.
- [ ] 🎤 in the chat box dictates in Hebrew after you last wrote in Hebrew.
