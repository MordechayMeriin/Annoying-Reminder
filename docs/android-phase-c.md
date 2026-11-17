# Android: Phase C (reminders, and the backend moves to AWS Lambda)

Implements Phase C of [the Android plan](android-plan.md): reminders are back, decided by the phone itself with
the server's rules. Check-ins, meetings, hard stops, the morning plan, mid-day check-ins, the evening review, the
week's and tomorrow's meetings, re-entry after a lapse and the Sunday cleanup all come back at the right loudness,
with the server's template wording. The LLM comes back in Phase D. Added at your request: **the backend moves from
Render to AWS Lambda** (sign-in, the backup, and a new daily "is the phone still alive?" email). This file is
written to be pasted into the pull request description.

## What you do

**Part 1: the reminders** (only the merge)

1. **Merge this into `main`.** GitHub Actions uploads the app to Play. Update it on the phone and open it once.
2. Within a minute, Diagnostics shows *Next reminder check* and *Last tick*, and the log has `[tick]` lines.
   The first morning plan arrives at the next morning-plan time (07:30 unless you changed it).
3. Check **Setup** once more: *Alarms & reminders* and Samsung's *never sleeping apps* matter now. Reminders are
   real from here on.

**Part 2: the backend on Lambda** (about 15 minutes, all in the browser)

1. **Run the setup in AWS CloudShell.** In the AWS console, switch to the region of your DynamoDB table (the
   `AWS_REGION` on Render), click the `>_` icon (CloudShell) at the top, and run:
   ```
   git clone https://github.com/MordechayMeriin/Annoying-Reminder.git && cd Annoying-Reminder/backend
   python3 scripts/aws_lambda_setup.py
   ```
   It asks for three values from **Render → annoying-reminder-api → Environment**: `GOOGLE_CLIENT_ID`,
   `OWNER_GOOGLE_EMAIL` and `ACTION_SECRET` (typed, not shown). Use the same `ACTION_SECRET`: that keeps the phone
   logged in. If AWS sends an email to verify your address (for the alive check), click its link. The script is
   safe to run again.
2. **GitHub → Settings → Secrets and variables → Actions → Variables**: add `AWS_DEPLOY_ROLE_ARN` and `AWS_REGION`
   (the script prints both). Then **Actions → Backend → Run workflow** (on `main`). It runs the tests, deploys,
   and ends with *Live at https://….lambda-url….on.aws*.
3. **Check the phone can reach it.** Open `https://<that address>/health` in the phone's browser. It should show
   `{"ok":true}`. Your HTTPS filter cut off Cognito before, so check this *before* the next step. If it's blocked,
   ask for `*.lambda-url.<region>.on.aws` to be allowed, or stop here: everything keeps working on Render.
4. **Point the app at Lambda**: add the variable `API_URL` = that address (no `/` at the end), then **Actions →
   Android → Run workflow** with *upload* ticked. Update the app. Diagnostics → *Back up now* should work.
5. **Switch Render off**: in Render, suspend both services (annoying-reminder-api and annoying-reminder-web), and
   turn off the cron-job.org job. Phase F deletes them for good.

**Done when:** a week of real reminders arrives when it should, and the tick log explains every skip.

## What's new

### Android: the rules (`logic/`), ported one for one
- **`Logic.kt`**: all of `backend/app/logic.py`. That covers the guardrails (quiet hours, Shabbat, min gap,
  daily cap, per-task cap, focus hold), loudness, recurrence, meeting occurrences, chips, stages, playbooks,
  Today's 3, *What now?*, sessions, avoidance, the lapse, re-entry, fences and the Sunday merge. Python's dicts
  are `TaskRow`s, read through small extension properties (`task.due`, `task.remind`…). One addition:
  `contactReasons` names each rule that holds a message back, for the log; `contactDecision` takes the latest one,
  exactly as before.
- **`RestCalendar.kt`**: `jewish_calendar.py`. KosherJava Zmanim says which days are Shabbat and yom tov (in
  Israel, or two-day outside), with hdate's Hebrew names. The sun times use hdate's own formula (see the
  decisions below), so the periods are the server's to the minute.
- **Tests**: `test_logic.py`, `test_logic_adhd.py`, `test_logic_phase2.py`, `test_logic_phase3.py`,
  `test_guardrails.py` and the calendar parts of `test_shabbat.py`, ported case by case. Plus a table of 61 rest
  periods printed by the server's code (2026–2027, Israel and outside; Rosh Hashana on Shabbat, Yom Kippur,
  Sukkot, Pesach, Shavuot). All match to the minute.

### Android: the tick (`remind/Ticker.kt`, `remind/Templates.kt`)
`scheduler.py` and `coach.py`'s templates, in one transaction per tick:
- **Due tasks**: Today's 3, asked-for reminders, deadlines within 48 hours, meetings and fences go through the
  guardrails. What may go now goes as ONE message, meetings and fences first. The rest is postponed to the
  moment the rule ends. The message is as loud as its most pressing task: normal; urgent for meetings, fences,
  asked-for reminders, important tasks or ignored check-ins; an alarm for an important task with a near deadline
  ignored twice, ringing at most an hour and never into quiet hours or Shabbat. Afterwards come the follow-up
  times: a meeting once more at its start; an escalating task within the day; anything else once a day.
- **Gentler pressure**: an avoided task gets one reason message (Too big · Unclear · Boring · Not now), one more
  try 3 days later, then a silent 7-day park. Only meetings, fences, asked-for reminders and near deadlines are
  ever loud. A lapse (48 hours without a message or a tap, Shabbat and holidays not counted) stops task
  check-ins and the mid-day and evening messages. The next morning brings the re-entry message, once. A hard
  stop held back more than 30 minutes is skipped, not sent late.
- **Daily messages**: the morning plan (Today's 3 picked by code and saved, today's meetings; on the first day of
  the week also last week's wins, the cleanup with Drop them · Keep, and the week's meetings when their times are
  within an hour). Also the mid-day check-ins (skipped if you were active in the last hour), the evening review,
  and the week's and tomorrow's meetings. Each runs once a day within its window, never on Shabbat. A job whose
  window reaches past Shabbat's end runs right after it.
- **Focus sessions** (from the backup) that nobody answered are closed as paused. Starting one is Phase E.
- **Tests** (`TickerTest`, 34): the flows of `test_tick_flow.py`, `test_phase3_flow.py`, `test_shabbat.py` and
  `test_meeting_digests.py` on a real in-memory database. That includes four silent days giving exactly one
  re-entry message and nothing loud but the meeting, and the first morning of the week's exact text.

### Android: running it (`remind/Reminders.kt`)
- **One exact alarm** for the next moment something could happen: a reminder, a daily message, Shabbat's end, a
  session's end. It's set after every tick and after every change you make. On Shabbat the next wake-up is its end.
- **Safety nets**: the boot receiver runs a tick after a restart or an app update, and now also after a clock or
  time-zone change. WorkManager runs a tick every 15 minutes regardless.
- **Notifications**: the normal, urgent or alarm channel. Alarm-level messages use the full-screen alarm and ring
  until answered or their window ends. A check-in about one task has **✓ Done** and **Later** (in Hebrew when the
  chips are). They work without opening the app and count as answering.
- **Diagnostics**: *Next reminder check*, *Last tick*, a *Check reminders now* button, and the tick log. Each
  tick writes what it sent and why it held anything back (`Held #12 "Taxes" until Sun 07:00: quiet hours`,
  `min gap, 12 of 20 min`, `one check-in a day`, `Shabbat / holiday`…).
- **Settings**: the web app's three loudness settings are back under *How loud*, with the same keys:
  urgent after N ignored check-ins, alarm for (critical / important / any / never), alarm after N.
- **Tests** (`RemindersTest`, 6): on a simulated Android 10. A due reminder becomes an urgent notification with
  ✓ Done and Later, and the next exact alarm is set. An alarm-level message is full-screen and insistent with its
  timeout. Done on the notification completes the task. Nothing runs before the handover.

Android tests: 230 in all (84 before), plus lint and the screenshots (new: *6b_diagnostics_ticks*, the tick log).

### Backend: AWS Lambda (`backend/app/lambda_app.py`, `alive.py`)
- **The function** serves what the phone needs: `GET /health`, `POST /auth/google`, `GET /sync/status`, and
  `POST /sync/restore` and `/sync/upload`. It's the same `auth.py`, `sync.py` and DynamoDB code as on Render, in a
  small FastAPI app behind a Lambda function URL (Mangum). AWS credentials come from the function's role, so there
  are no keys to keep. A login token from Render keeps working, because it's the same `ACTION_SECRET`.
- **The alive check**: EventBridge runs it daily at 06:00 UTC. If the phone hasn't backed up for 2 days, it
  emails you through SES, from your own verified address to itself, at most once a day while it lasts. The phone
  backs up at least daily (the heartbeat), and after every tick that sent something.
- **`scripts/aws_lambda_setup.py`**: one run in CloudShell creates the function's role (this table, this email,
  its logs), the function, its URL, the daily schedule, and the GitHub OIDC role. GitHub may only update this
  function's code, from `main`. Checked against moto, twice in a row (safe to re-run).
- **`.github/workflows/backend.yml`**: the backend's tests on every change, and on `main` the deploy (packaged for
  Lambda's Linux, about 9 MB) followed by a `/health` check.
- **Tests** (`tests/test_lambda.py`, 10): function-URL requests (health, login required, an old Render token still
  works, upload, a bad row → 422, the login rate limit), the daily event, and the alive check (nothing before the
  handover, alive, one email a day while silent). All 411 backend tests pass.

## Decisions and deviations from the plan

1. **Sun times: hdate's formula, not KosherJava's astronomy.** On Render, hdate ran without its optional `astral`
   package. So it used NOAA's low-accuracy solar equations, ignored altitude, and cut times to the whole minute.
   KosherJava's more precise times came out 1–3 minutes away from those, which would fail `test_shabbat.py`
   ("a test that passes in Python must pass in Kotlin"). So the 20-line formula is ported, and KosherJava does the
   hard part: the Hebrew calendar and which days are yom tov. The 60-minute margins swallow either difference.
   The altitude setting stays but does nothing, as on the server.
2. **The backend moved to Lambda now** (your addition), and only what the phone uses moved. The web app's
   endpoints, `/tick`, Pushover and browser push stay behind on Render. After the handover they're switched off
   anyway; their code is removed in Phase F. Until you suspend Render, it's still the emergency way back
   (Phase B, decision 6).
3. **Function URL, not API Gateway**: free, nothing to configure, and the app does its own login check as before.
4. **Notification buttons are ✓ Done and Later**, not ▶ Start 5 min: starting a session is Phase E.
5. **No LLM yet**: every message is the server's template (its fallback when the LLM was down). That's the plan's
   "template wording". The coach's follow-up times come back with it in Phase D.
6. **No lease**: the server leased tasks so two overlapping ticks couldn't both send. The phone runs one tick at
   a time (a lock), in one transaction, so it doesn't need one.
7. **Daily jobs are claimed on the phone only** (`meta`, not backed up). After a reinstall, today's morning plan
   could come a second time. That's rare and harmless.
8. **No database migration**: the new queries work on version 1's tables.
