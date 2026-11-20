# Code review: Annoying Reminder (whole repo at `edb01ad`, 2026-11-20)

Scope: the whole codebase (~22k lines): the Android app, the Lambda backend and CI. Read in full: Ticker, Store, the
database layer (AppDatabase / Rows), sync on both ends, the assistant's model calls and tool loop (Llm, ToolLoop, Chat,
Agent, Context, Coach), the alarms (AlarmReceiver, AlarmRings, Reminders), the app class, the home screen wiring, and the
backend's `db.py`, `sync.py`, `auth.py`, `llm.py`, `llm_proxy.py`, `alive.py`, `lambda_app.py`. The rest was skimmed.
The tests were not run.

**Overall:** the architecture is sound and fits the problem. The phone is the brain and the backend is thin. Code
decides *when*, and the model only writes *words*, falling back to templates. Each tick has three steps (plan, write,
deliver), so a slow model never holds the database open. Comments and tests are unusually thorough. The problems are
mostly in the sync contract between phone and server, unbounded data growth, and some wasted work in the UI.

**Suggested order:** 1 (a silent, permanent backup failure with a small fix), then 2 and 3 (restore and backup paths you
only exercise in an emergency), then 9 and 10 (the cheapest performance wins).

---

## 🔴 High

### 1. A setting changed, reset, then changed again before a backup gets the backup stuck for good

- **Where:** [`android/.../data/Store.kt`](../android/app/src/main/java/com/prominder/myapp/data/Store.kt) (`saveSetting`, line 194) and [`backend/app/db.py`](../backend/app/db.py) (`write_batch`, line 262).
- **Cause:** resetting a setting to its default writes a tombstone for it. Saving a non-default value later doesn't
  remove that tombstone. The next upload then sends the same key twice: once in `tables.settings` and once in
  `deletes.settings`.
- **What the server does:** `write_batch` puts the writes and the deletes into one list, sent 25 at a time. DynamoDB
  rejects a `BatchWriteItem` that puts and deletes the same item (`ValidationException`), so the upload fails with a 500.
- **What the phone does:** it treats the 500 as a network error and retries. The tombstone is only cleared by a
  successful upload, so every daily backup fails from then on. The "phone silent" email (`alive.py`) then fires every
  day even though the phone is fine.
- **The other case:** if the two land in different groups of 25, the delete runs last and the setting silently
  disappears from the backup.
- **How it happens:** easily. Backups only run daily or when you leave the app on Wi-Fi, and the chat's
  `update_setting` tool can do the same thing.
- **Fix:**
  - Phone: when `saveSetting` writes a non-default value, delete the tombstone for that key in the same transaction.
  - Server (defence in depth): in `build_upload`, drop deletes for keys that are also written in the same upload.
  - Add a test: set → reset → set, then upload; expect one put and no delete.

### 2. Restoring the backup will stop working as the chat grows

- **Where:** [`backend/app/sync.py`](../backend/app/sync.py) (`export_all` / `restore`, line 122).
- **Cause:** `restore` returns every row in one response. A Lambda function URL can't return more than 6 MB.
- **Why it grows:** nothing ever trims messages or events, on the phone or the server.
- **When:** roughly 20 messages a day, each a few hundred bytes plus JSON keys, crosses 6 MB in one to two years.
  Restore fails exactly when you need it, after a lost or reset phone.
- **Fix:** page the restore (a cursor per table, the phone loops until done), or gzip the response, and/or keep only N
  months of chat on both ends.

### 3. A large upload can take longer than the Lambda is allowed to run, with no progress kept

- **Where:** [`backend/app/db.py`](../backend/app/db.py) (`write_batch`), [`backend/scripts/aws_lambda_setup.py`](../backend/scripts/aws_lambda_setup.py) (`TIMEOUT_S = 60`), [`android/.../sync/SyncEngine.kt`](../android/app/src/main/java/com/prominder/myapp/sync/SyncEngine.kt) (`BATCH = 1500`).
- **Cause:** the function has 60 seconds. `write_batch` can retry for minutes (40 attempts, up to 5 s each) while
  DynamoDB throttles it at about 11 write units a second.
- **Size of the problem:** one batch of 1,500 rows costs about 3,000 or more write units, because index writes and long
  messages add cost.
- **Result:** after a long offline stretch the request times out (504). The phone resends the whole batch every time,
  so it may never get through.
- **Fix:** smaller batches (around 200), and/or have the server return how far it got so the phone can continue.
  Item 4 also roughly halves the cost.

---

## 🟠 Medium: architecture

### 4. Much of the DynamoDB layout is left over from the web app and costs write capacity for nothing

- **Where:** [`backend/app/db.py`](../backend/app/db.py) (layout docstring), [`backend/app/sync.py`](../backend/app/sync.py) (entity keys; `mtask#` line 212, `children` line 259).
- **What's unused:**
  - Nothing reads GSI2 (a task's sessions).
  - Nothing reads GSI1 for tasks, messages or sessions.
  - Nothing reads the `children` or `mtask#` items, and they're never deleted.
- **What's used:** the only index read is events by kind, which `export_all` uses.
- **Cost:** every task or message write pays for one or two index writes, on a free tier that's already tight
  (and it feeds item 3).
- **Fix:** drop the unused index keys and the `children` and `mtask` items (and GSI2 itself, with its capacity moved
  back to the table).

### 5. Restore depends on a second copy of the list of event kinds

- **Where:** [`backend/app/sync.py`](../backend/app/sync.py) (`EVENT_KINDS` line 55, the per-kind loop line 130); the phone's copy is in [`StoreAssistant.kt`](../android/app/src/main/java/com/prominder/myapp/data/StoreAssistant.kt).
- **Cause:** events are read back with one query per kind, using the server's `EVENT_KINDS`. `build_upload` doesn't
  check the kind.
- **Result:** if a future app version adds a kind, those events upload fine and are silently lost on restore.
- **Fix:** store events in one partition with a sort key of `task#time#rid`. That replaces 14 queries with 1 and
  removes the duplicated list. Failing that, reject unknown kinds on upload.

### 6. Every change triggers a fresh replan with no debouncing

- **Where:** [`android/.../ReminderApp.kt`](../android/app/src/main/java/com/prominder/myapp/ReminderApp.kt) (`onChange`, line 47), [`Reminders.replan`](../android/app/src/main/java/com/prominder/myapp/remind/Reminders.kt).
- **Cause:** `Store.onChange` starts a new coroutine each time. It scans open tasks, works out Shabbat times and
  reposts the focus timer notification.
- **Result:** one chat turn with five tool calls replans five times. A tick replans twice, then sets its own alarm
  anyway.
- **Fix:** send changes into a `Channel(CONFLATED)` (or a debounced flow) with a single collector that replans.

### 7. `Store.write` passes its state through mutable fields

- **Where:** [`android/.../data/Store.kt`](../android/app/src/main/java/com/prominder/myapp/data/Store.kt) (`seq`, `dirty`, `userActed`).
- **What:** these live on the `Store` object. That's only correct because Room runs one write transaction at a time and
  nothing nests `write` (checked: nothing does today).
- **Risk:** easy to break later. A nested `write` would bump `seq` again and wipe the outer transaction's flags.
- **Fix:** pass a small per-transaction object (`seq`, `dirty`, `userActed`) to the block instead.

### 8. Every transaction bumps the change number, even one that wrote nothing

- **Where:** [`Store.kt`](../android/app/src/main/java/com/prominder/myapp/data/Store.kt) (`write`), [`SyncEngine.kt`](../android/app/src/main/java/com/prominder/myapp/sync/SyncEngine.kt) (`uploadLocked`).
- **What:** `write` always increments `META_SEQ`, so a tick that sent nothing still bumps it twice.
- **Result:** `upTo > after` is almost always true, and the daily backup sends an empty request. The comment "a tick
  that changed nothing doesn't schedule a backup" is only half true.
- **Fix:** only persist the new number when something was written.

---

## 🟠 Medium: performance

### 9. The home screen rebuilds the whole chat list on every keystroke

- **Where:** [`android/.../ui/DataRoutes.kt`](../android/app/src/main/java/com/prominder/myapp/ui/DataRoutes.kt) (line 115, `chatItems` at line 191).
- **Cause:** `draft` is read inside `HomeRoute`, so typing recomposes it. That calls `chatItems(...)` over 300 messages
  each time: a timestamp parse, a time-zone conversion, formatting and a `Chips.decode` JSON parse per message.
- **Made worse by:** `HomeState`, `ChatItem` and `ChipJson` hold plain `List`s. Compose treats those as unstable, so
  `HomeScreen` and the list items don't skip recomposition.
- **Fix:** wrap it in `remember(messages, zone, showModel) { chatItems(...) }`, move `draft` state into the input
  composable, and use `ImmutableList` or `@Immutable` on the UI models.

### 10. The screens keep querying the database while the app is in the background

- **Where:** [`ui/DataRoutes.kt`](../android/app/src/main/java/com/prominder/myapp/ui/DataRoutes.kt) (lines 60, 78, 95 onwards).
- **Cause:** the routes use `collectAsState`, not `collectAsStateWithLifecycle` (which `AppRoot` already uses).
- **Result:** while backgrounded, every tick's write still re-runs the home screen's seven or so Room queries,
  including the 300-message one.
- **Fix:** switch every `collectAsState` in the routes to `collectAsStateWithLifecycle`.

### 11. Missing indexes on queries over tables that grow forever

- **Where:** [`android/.../data/AppDatabase.kt`](../android/app/src/main/java/com/prominder/myapp/data/AppDatabase.kt), [`Rows.kt`](../android/app/src/main/java/com/prominder/myapp/data/Rows.kt).
- `messagesSince(created_at)` scans the whole messages table. It runs every tick (`contactState`) and every chat turn.
  Add an index on `created_at`, or query by id.
- The `changed*` queries and `countChangedAfter` scan all 8 tables, because no table has an index on `seq`.
  - Diagnostics makes this worse: its sync summary ([`AppRoot.kt`](../android/app/src/main/java/com/prominder/myapp/ui/AppRoot.kt) line 228) re-runs on every new log line, and a single tick writes dozens of lines.
- `messagesForTask` matches with `LIKE '%,5,%'` on JSON text, which can never use an index. A small table linking
  messages to tasks would fix it.
- These need a Room migration (version 2), with the schema exported to `android/app/schemas/`.

### 12. The tick writes its messages one after another

- **Where:** [`android/.../remind/Ticker.kt`](../android/app/src/main/java/com/prominder/myapp/remind/Ticker.kt) (line 103).
- **What:** `pending.map { compose }` runs the model calls sequentially inside a shared 30-second budget. A morning plan
  plus a check-in at the same moment leaves the second one about 5 seconds, so it usually falls back to a template.
- **Fix:** run them in parallel with `async` / `awaitAll` under the same deadline.
- **Smaller point:** a tick fetches `dao.openTasks()` about five times. Load it once per run.

### 13. The diagnostic log does file I/O on the main thread

- **Where:** [`android/.../diag/DiagLog.kt`](../android/app/src/main/java/com/prominder/myapp/diag/DiagLog.kt) (lines 32 and 46), [`AppRoot.kt`](../android/app/src/main/java/com/prominder/myapp/ui/AppRoot.kt) (line 227).
- **What:** `Diag.log` appends to a file and checks its length on every call, often from UI callbacks.
  `Diag.log.lines()` reads up to 256 KB while the Diagnostics screen is composing.
- **Fix:** write through a single-threaded dispatcher or queue, and load the lines with `produceState` on
  `Dispatchers.IO`.

---

## 🟡 Low: quality

- **Comments point at deleted code.** Sixteen-plus comments say "ported from `backend/app/scheduler.py` / `tasks.py` /
  `actions.py` / `thread.py`", but Phase F removed those files. The `db.py` docstring still describes the web app's
  table layout. Rewrite them to describe what the code does, or link the commit.
- **Kinds and statuses are plain strings everywhere.** `"task"`, `"meeting"`, `"fence"`, `"open"`, message kinds, chip
  actions and setting keys (`s.int("min_gap_min")`) are all strings. A typo only shows up at runtime. Enums, sealed
  types or typed setting accessors would catch them at compile time.
- **`Logic` is 735 lines in one object** (chips, texts, guardrails, stages, picking the next task). Splitting it by
  topic would make it easier to work in. It's pure code with good tests, so this isn't urgent.
- **A check-in can mention a task you just finished.** `Ticker.send` re-reads the group and drops closed tasks, but
  keeps the text that was written for the original group.
- **The home screen's dates don't roll over by themselves.** `HomeRoute` reads `store.now()` while composing, so `day`
  and `todayStart` only update when something else redraws the screen. If the app stays open past midnight or the
  day-reset time, the focus card can show the wrong day.
- **Backend:** the per-minute rate limits (`/auth/google`, `/llm`) are in memory, so each Lambda instance counts
  separately. The DynamoDB daily cap is the real limit; say that in the code. `_count_today` also counts calls that
  then fail with a 503.
- **Notification reply vs. chat:** `ReplyWorker` reads `chat.turn.value.error` after its turn ends, and that can
  belong to a different turn. Return the error in the result instead.
- **Notification button errors are logged as tick failures.** `AlarmReceiver.background` logs every failure (✓ Done,
  Later, session end) under the `tick` tag as "Failed: …", which makes Diagnostics misleading. Pass the tag in.

---

## ✅ Worth keeping as they are

- The three-step tick (plan, write, deliver), and "nothing counts as sent until it's delivered".
- Tracking changes by change number with tombstones is simple and correct, apart from item 1.
- One exact alarm plus safety nets (restart, clock change, a 15-minute WorkManager run) instead of polling.
- Template fallback everywhere: a reminder never depends on the model answering.
- The checks in the tool loop: one retry for bad arguments, parsing a tool call written as text, and a timeout on the
  whole turn.

---

## Status: all findings fixed

| # | What was done |
|---|---|
| 1 | Saving a non-default setting deletes its tombstone (`Store.saveSetting`); the Room 1 → 2 migration removes any stale one. The server writes a key that's both written and deleted in one upload, never both, and `write_batch` writes each key once. Tests on both ends. |
| 2 | Restore comes in pages (`paged` + `cursor`, about 1.5 MB each) and is gzipped. The phone saves all pages in one transaction. App versions before paging still get everything in one answer. |
| 3 | The phone sends 200 rows per request, in change-number order, and remembers each request that got through. The server gives up with 503 "busy" after 40 s instead of running into the Lambda's timeout. |
| 4, 5 | Layout 2: no secondary indexes, events in one partition (`event` / `<task_id>#<time>#<rid>`, one query, no second list of event kinds), no `children` or `mtask#` items. `sync.migrate()` moves an old table resumably: in the daily run, before a restore, and from `scripts/aws_lambda_setup.py`. |
| 6 | `Replanner`: changes are conflated, so a burst of changes is one plan. |
| 7 | `Store.write` keeps its state in the transaction's coroutine context; a nested `write` joins the outer change. |
| 8 | A change that wrote nothing doesn't use up a change number. |
| 9 | Chat lines are `remember`ed; the draft is read only by the message field; the UI models are `@Immutable`. |
| 10 | Every route collects with `collectAsStateWithLifecycle`. |
| 11 | Room version 2: `seq` indexes on all 8 tables, `messages.created_at`, and a `message_tasks` link table instead of the `LIKE`, with a migration test. Diagnostics refreshes at most every 2 s. |
| 12 | The tick writes its messages in parallel and reads the open tasks once per step. |
| 13 | `DiagLog` writes on one background thread; Diagnostics reads it on `Dispatchers.IO`. |
| Low | Comments describe the code (or name commit b09e82e for removed tests); task kinds, message kinds, event kinds, roles and chip actions are constants (`data/Kinds.kt`) and settings have typed accessors; `Logic` is split by topic (`Schedule`, `Loudness`, `Contacts`, `ChipRules`, `Coaching`, `Focus`, `FocusSessions`, `Texts`, `Pressure`); a check-in whose task closed while it was being written is rewritten for the tasks still open; the home screen's clock moves on every minute and on returning to the app; the per-minute limits say they're per instance, and a call that got a 503 doesn't count toward the daily cap; `ReplyWorker` gets its own turn's error; button failures are logged under `notify`. |

**One step for the owner (optional):** run `python3 scripts/aws_lambda_setup.py` again in CloudShell. It moves the data
if the daily run hasn't finished yet, drops the two unused indexes, and gives their capacity to the table (25/25).
Until then everything works, and new writes already skip the indexes.
