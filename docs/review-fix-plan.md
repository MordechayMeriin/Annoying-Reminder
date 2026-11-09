# Fix plan: code review of 9 Nov 2026

Source: the "Annoying Reminder Code Review" artifact (reviewed commit `426cd06`, = `origin/main`).
All 21 findings are covered below: #1–#11 plus the 10 low ones (L1–L10).

**Base:** the review is of `origin/main`. Local `chat-coach` and `main` are behind it, so all work
goes on a new branch cut from `origin/main` (e.g. `review-fixes`), one commit per phase and one PR at the end.
Line numbers below refer to `426cd06`.

---

## Phase 1: server responsiveness (#1, #2, L2, L3, L4)

The biggest win. Three parts that only work well together: a pool makes each query cheap, offloading
keeps the event loop free, batching cuts the query count.

### 1a. Connection pool (#2)
- `backend/app/db.py`: replace `psycopg.connect` per call with one lazily created
  `psycopg_pool.ConnectionPool(conninfo, min_size=1, max_size=5, kwargs={"row_factory": dict_row,
  "prepare_threshold": None}, check=ConnectionPool.check_connection, max_idle=300)`.
  `connect()` keeps its signature (`with pool.connection() as conn`), so no caller changes.
- `check_connection` matters: the Supabase pooler drops idle connections, and without the check the first
  query after a quiet hour fails.
- Close the pool on shutdown (FastAPI lifespan in `main.py`).
- `requirements.txt`: add `psycopg-pool>=3.2`.

### 1b. Get blocking work off the event loop (#1)
- `main.py` `chat()`: make `emit` thread-safe:
  `loop.call_soon_threadsafe(queue.put_nowait, event)`. Then any code can report progress from a worker thread.
  (`asyncio.Queue` is not thread-safe, so this has to come first.)
- `llm_loop.run`: `result = await asyncio.to_thread(execute, name, args, ctx)`; build the tool status in
  the same thread call (it reads the DB).
- `agent.handle_user_message`: run the pre-LLM block (history, `thread.add`, candidate selection,
  `likely_topic`) in `asyncio.to_thread`, split so that `received` is emitted right after `thread.add` returns.
  Same for `thread.link` and the final `thread.add`.
- `scheduler._deliver`: the part before `coach.compose` and the part after it (save message, `message_level`,
  `notify.notify`, `_after_contact`) each go into one `to_thread` call. `coach.compose`'s own context
  building gets the same treatment.
- `scheduler.run_daily_job`: `await asyncio.to_thread(run_meeting_digest, job)`; `_snapshot_text` and
  `thread.add`/`notify.notify` via `to_thread`.
- `main.py` `/chat` already calls `cancel_alarms` in a threadpool; leave it.

### 1c. Fewer queries per turn (#2 measurement, L2)
- `tasks.py`: add batch reads `subtasks_many(ids)`, `recent_events_many(ids, n)` (window function,
  `row_number() over (partition by task_id order by created_at desc)`), `event_counts_many(ids, days)`.
- `context.py`: `task_contexts(tasks, now_local)` uses those; `agent.likely_topic` calls it once instead of
  `task_context` per candidate (9 queries → 3).
- `coach._snapshot_text`: `event_counts_many` instead of one call per task (up to 12 → 1).
- `scheduler.ignored_streak` / `_after_contact`: read `thread.last_user_reply_at()` once per delivery and
  compare per task, instead of `user_replied_since` per task.
- `agent.tool_status`: look the title up in the tasks already loaded for the turn (candidates), and only fall
  back to `tasks.get`.

### 1d. contact_state (L3)
- `thread.contact_state`: `select created_at ... order by created_at desc limit 1` for the last proactive message,
  plus a count limited to `created_at >= day_start`. Check `db/schema.sql` for an index on
  `messages (kind, created_at)`; add a migration if it's missing.

### 1e. Shared HTTP clients (L4)
- `llm.py` and `auth.py`: one module-level `httpx.AsyncClient` each (created lazily, closed in the lifespan),
  timeout passed per request.

**Tests:** existing 137 must pass. Add: a test that `emit` from a worker thread reaches the queue; a storage-call
count test for one chat turn (the review's scenario: 20 calls, 18 before the first model call), asserting
the new count. Fakes need the new batch functions.

---

## Phase 2: notifications (#3, browser push switch)

### 2a. Browser push becomes a switch
Decision (from you): keep browser push, but make it switchable. Since it stays in the code, its bugs get fixed too (2b).

- `config.py`: new spec `Spec("browser_push", "bool", "true", "Browser notifications", group, help=...)`.
  Rename the group "Pushover" → "Notifications" so both switches sit together in ⚙ Settings.
  An app setting rather than an env var because every other delivery choice already lives there and can be
  flipped without a redeploy.
- VAPID env vars become optional (`_opt`). `Settings.browser_push_on` property = setting on *and* keys present.
- `notify.notify()`: new routing. When browser push is off:
  - Pushover is used for every message whatever `pushover_mode` says (`pushover_mode` only describes how the
    two channels combine);
  - no fallback to browser push when Pushover fails;
  - if Pushover isn't configured either, log an error.
  This routing is the part to write by hand: see "Learn by doing" below.
- `notify.send_push()`: returns 0 straight away when off (no DB read).
- `main.py`: `/config/push` returns `{"enabled": bool, "vapidPublicKey": str | null}`; `/push/test` returns
  400 when off; `describe()` gets a `browserPush` block like `pushover`.
- `web/app.js` `setupPush()`: still registers `sw.js` (install and notification actions need it), but when
  `enabled` is false it hides "Enable notifications" and skips subscribing. Adjust the `pushoverBox` text
  ("Until then everything goes to browser push").
- Tests: extend `test_modes` with browser-off cases (each Pushover mode × Pushover ok / failing / not configured).

### 2b. webpush bugs (#1 third bullet, #3)
- `webpush(..., timeout=10, headers={"Urgency": "high"})`. One endpoint that never answers can no longer stall
  a delivery forever.
- Urgency: `high` for every proactive message (it's a one-person reminder app, the whole point is arriving on time).
- TTL: keep 3600 s on purpose (a reminder that's over an hour late is noise; Pushover covers the
  "must arrive" case) and say so in a comment.
- Catch `requests.exceptions.RequestException` (timeouts) next to `WebPushException`, log it and go on to the next subscription.

---

## Phase 3: the chat turn (#4, #5, #11, L9, L10)

### 3a. Show the reply from `done` (#4)
- Server: `done` also carries `messages`: the saved user and reply rows in the same shape as `/messages`
  (ISO time, task ids), so the client doesn't have to rebuild them.
- `web/app.js` submit handler: `mergeMessages(res.messages)`, render, then `loadLatest()` in the background.
- Crash fix: keep a local `const sent = outgoing;` before clearing `outgoing`, and read `sent.id` in `catch`.
  Move the background sync out of the `try`, so its failure can't be taken for a send failure.

### 3b. Don't log out on a Supabase outage (#5)
- `auth._user_id_for`: 200 → id; 401/403 → `None` (→ 401, logs out); anything else, and `httpx.HTTPError`,
  → `HTTPException(503, "Can't check your login right now")`.
- `authFetch` already logs out only on 401, so the client shows the toast and keeps the session.

### 3c. Overall time limit for a turn (#11)
- New env var `CHAT_TURN_TIMEOUT_S` (default 60, a spend guard next to `LLM_TIMEOUT_S`).
- `agent`: `asyncio.wait_for(run_tools(...), timeout)`. On timeout, save and return
  "That took too long, so I stopped. Anything I already changed is saved." (the `finally` that links tasks still runs).

### 3d. Small edges (L9, L10)
- `sendChat`: wrap `JSON.parse` in try/catch; skip a malformed progress event (`console.warn`), only fail on a
  malformed `done`.
- After send: scroll to the bottom only if the view was near the bottom when `done` arrived.
- `MAX_TEXT` clipping: a pure `clip(text, n)` in `logic.py` that cuts at the last whitespace and adds "…".
  Use it in `coach.py` wherever `[:MAX_TEXT]` is used. Unit-test it (Hebrew, no spaces, exact length).

---

## Phase 4: web app UX (#6–#10, L1, L6–L8)

### Back button closes panels (#6)
- `openDrawer` / `openTask` / `openSettings` push a history entry (`history.pushState({panel})`).
- `popstate` closes the top panel. Closing with ✕ / backdrop / Esc calls `history.back()` when we pushed an
  entry, so the history stays balanced.
- Unsaved settings: if `confirm()` is refused on `popstate`, push the entry again.

### Render only what changed (#7)
- `renderMessages()`: if the loaded ids are the rendered ids plus new ones at the end, append only those
  (and a day separator when the day changes); full re-render only after "Load earlier" or a change in an
  existing message (compare a small signature: id + task_ids + task_status).
- Server: `/messages?after=<id>` → `thread.page_after`; the 60 s poll and the visibility refresh use it.

### Startup screen and a pinned library (#8)
- Pin an exact supabase-js version in the import (look up the current 2.x release when implementing). Self-hosting
  the bundle would remove the esm.sh dependency entirely; pinning is the smaller step.
- `index.html`: a small loading screen visible by default; `app.js` hides it after the session check.
  An inline script shows "Couldn't load. Retry" after 10 s if `app.js` never started (covers esm.sh being down).

### Done: undo and bigger targets (#9)
- Delay the Done request instead of adding a server-side "reopen": the task disappears at once, the toast
  shows "Done ✓ · Undo" for 5 s, and the request is sent when the toast ends (or right away on
  `visibilitychange` → hidden). Undo puts the task back. That avoids undoing recurrence rollover on the server.
- `style.css`: task buttons and header buttons at least 44 px, 12 px apart.

### One request for the drawer (#10)
- `GET /tasks/overview` → `{today, upcoming, open}` (one auth check, one pooled connection).
  `loadTasks()` uses it.

### Low ones
- **L1 duplicate polling:** register the `visibilitychange` listener and the interval once (module-level flag);
  `showLogin` stops the interval.
- **L6 input covers messages:** in `autosize()`, set `#main`'s bottom padding from the composer height.
- **L7 Enter on phones:** on `(pointer: coarse)`, Enter adds a new line and only the button sends.
- **L8 "Task #12" chips:** `thread.page` returns titles for all linked tasks
  (`task_titles` as a JSON object from a subquery over `task_ids`); `messageEl` uses it.

---

## Phase 5: check-in job keys (L5)

- `scheduler.due_daily_jobs`: claim `checkin@13:00` instead of `checkin{i}`.
- Deploy outside a check-in window: on the deploy day an already-claimed `checkin0` doesn't match the new key, so
  one check-in could repeat. A short-lived issue, not worth a migration.

---

## Learn by doing (your part)

In Phase 2a, `notify.notify()` routing with the browser switch: about 8–10 lines that decide which channels a
message goes to from (browser on?, Pushover mode, level, did the first channel succeed?). I'll set up the
setting, the tests' cases and a `TODO(human)` in the function, and you write the decision.

## Verification

- `pytest` after each phase (137 now, plus the new tests listed above).
- Phase 3–4 by hand in the browser pane against a local API: send a message (reply appears without a second
  request), Back closes panels, Done → Undo, the loading screen on a throttled network, Supabase 5xx
  simulated by pointing `SUPABASE_URL` at a dead port (the app stays logged in).
- After deploy: watch Render logs for pool errors in the first quiet hour (stale connections).
