# Android: Phase B (the handover: your data moves to the phone)

Implements Phase B of [the Android plan](android-plan.md): the phone gets its own database with your tasks, Today's
3, the chat history and your settings, works offline, and backs everything up to the server a few minutes after
each change. You add, edit, finish, postpone and cancel tasks by hand. No reminders yet (Phase C) and no
assistant yet (Phase D). This file is written to be pasted into the pull request description.

## What you do

1. **Merge this into `main`.** Render deploys the backend (it needs the new `/sync` endpoints) and GitHub Actions
   uploads the app to Play. Wait until both are done (Render → annoying-reminder-api → Events says "Deploy live";
   the Play Store offers the update).
2. **Update the app on the phone** and open it. It shows *Move your data to this phone*. Read it, tap the button.
   The server can take a minute to wake up.
3. That's the handover. From then on:
   - the phone is the only app. The web app answers "Your data now lives in the ProMinder Android app" and the
     server's `/tick` does nothing, so cron-job.org can stay on for now (it's switched off for good in Phase F);
   - there are **no reminders until Phase C** (accepted in the plan).

Nothing to set up: the backup uses the same login and the same DynamoDB table as before.

**Done when:** the app shows your real tasks and chat in airplane mode, and reinstalling it restores them
(uninstall → install from Play → sign in → *Restore my data*).

## What's new

### Backend (`backend/app/sync.py`, three endpoints in `main.py`)
- `POST /sync/restore`: every row the phone keeps, in the stored format (times as fixed-width UTC text, ids as
  numbers), plus the id counters. With `handover: true` the first one marks the data as the phone's
  (`pk=sync, sk=status` in DynamoDB). Later restores (a reinstall) keep the original handover time.
- `POST /sync/upload`: the rows the phone changed, written into the same items the web app used (index keys,
  a parent's step list, message links and id counters included), so the old server could still read them.
  Every row is checked before anything is written; a bad one answers 422 with the reason.
- `GET /sync/status`: handed over? last upload? (Phase C's "is the phone alive?" email reads the last upload.)
- After the handover a middleware answers 409 on every web-app endpoint, and `/tick` returns
  `{"handedOver": true}` without doing anything.
- Tests (`tests/test_sync.py`, 12): restore of every kind of row, a byte-for-byte round trip through an empty
  table, uploads read back through the old modules (`tasks.subtasks`, `thread.for_tasks`, counters…), bad rows
  writing nothing, the handover switching the web app off, and the Android tests' sample still matching the
  stored format (`scripts/make_android_sync_sample.py` regenerates it).

### Android
- **The database** (`data/`): Room, one table per server item kind with the same field names
  (`Rows.kt`), schema version 1 exported to `android/app/schemas/` for future migrations.
- **The rules** (`data/Store.kt`), ported from `tasks.py` / `actions.py`: create (a meeting reminds
  `meeting_lead_min` before), edit (a new time resets the nagging and ends a park; the old description goes into
  the history), ✓ Done (closes open steps; a recurring task rolls on to its next time), ⏰ Later (decided by code:
  +2 h before 18:00, else the next morning plan), cancel (takes open steps with it), Today's 3 (at most three,
  open tasks only, the day starts at the day-reset time). Each also writes the same chat line the server did
  ("[Marked done: …]"), so the assistant will see them in Phase D. Date rules in `DayMath.kt`, settings and their
  checks in `SettingSpecs.kt`, the lists in `TaskLists.kt`.
- **The backup** (`sync/`): each change gets a change number; the upload sends rows newer than the last upload,
  plus deletions, in requests of up to 1,500 rows, and only marks them sent when all arrived. WorkManager runs it
  2 minutes after a change, right away when you leave the app, and once a day (which also tells the server the
  phone is alive). A restore only ever goes into an empty phone, so it can't overwrite anything.
- **Screens**: the handover / restore screen; home (Today's 3 with ✓ and *Change*, the chat history, ＋ New task);
  Tasks (Today: overdue & today, coming up; All open); a task (next step, where you stopped, description,
  steps, history, ✓ Done, ⏰ Later, ☆ Today's 3, Edit, Cancel); the task form (kind, date and time, repeat,
  importance, next step, estimate, description); ⚙ Settings (the web app's, minus Pushover and browser push) and
  what you asked of the assistant. Phase A's test buttons moved into Diagnostics, which now also shows the
  backup (last backup, changes waiting, last error) and has *Back up now*.
- **Logging out keeps your data** on the phone: it's the live copy, and changes not yet backed up would be lost.
- Tests: 84 in all: the date rules and settings against the Python tests' cases, every store
  operation on an in-memory Room database, the backup against a fake server using a real restore answer made
  by the backend (decoded and re-encoded byte for byte), failures and retries, the daily heartbeat, batching.
  New screenshots: home, tasks, task, form, settings, handover, restore.

## Decisions and deviations from the plan

1. **The backup lives on the existing backend (Render + DynamoDB), not a new Lambda + Supabase.** The plan was
   written before the move to DynamoDB and to Google sign-in. The phone already reaches `onrender.com` through the
   filter (it cut off Cognito, and might cut off AWS's Lambda addresses too), the login token already works there,
   and the DynamoDB code and its tests exist. The Lambda can still come with the LLM in Phase D if it's worth it.
   Render's cold start doesn't matter for a background upload.
2. **No `updated_at` migration on the server.** The phone tracks what changed itself (change numbers), so the
   server needs nothing new in its items.
3. **Times are stored as text, not numbers**, in the server's exact format: a row restored and uploaded again is
   identical (no lost microseconds, so no duplicate history entries), and text in that format sorts like time.
4. **No cron-job.org step before the handover**: `/tick` does nothing once the phone owns the data.
5. **⏰ Later is the code-decided time** (as the web app's *Later* button), not a time picker: the ADHD brief's
   "the app does the work".
6. **To give the data back to the web app** (only if the phone app had to be abandoned): delete the item
   `pk=sync, sk=status` in the DynamoDB console (Explore items → prominder), and within a minute the web app works
   again with whatever the phone backed up.
