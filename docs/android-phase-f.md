# Android: Phase F (clean up)

Implements Phase F of [the Android plan](android-plan.md). The phone has done everything since Phase E, so this
phase removes what's left of the old setup. The backup runs once a day, plus a cheap run on Wi-Fi when you leave the
app, instead of a few minutes after every change. The web app, the Render server and its Python logic leave the
repository. `backend/` keeps only what the Lambda runs. Render, cron-job.org and Pushover can be deleted for good.
This file is written to be pasted into the pull request description.

## What you do

1. **Before merging, check one GitHub variable.** GitHub → Settings → Secrets and variables → Actions →
   Variables: `API_URL` must be the Lambda's address (`https://….lambda-url.<region>.on.aws`, set in Phase C). The
   app has no built-in address any more, and a release now refuses to build without it, or with Render's.
2. **Merge this into `main`.** Two workflows run:
   - *Android* uploads the app to Play. Update the app on the phone.
   - *Backend* runs the tests and deploys the smaller Lambda. It ends with *Live at https://…*. Nothing about the
     function's settings changes, and the phone stays logged in.
3. **Check on the phone:** Diagnostics → *Back up now* works, and *Waiting for backup* goes back to 0.
4. **Delete the old services** (Render's two have been suspended since Phase C):
   - **Render** → `annoying-reminder-api` → Settings → *Delete Web Service*. The same for
     `annoying-reminder-web`. If nothing else is on the account, you can delete the account too.
   - **cron-job.org** → Cronjobs → the `/tick` job → Delete.
   - **Pushover** → [pushover.net/apps](https://pushover.net/apps) → the *Annoying Reminder* application →
     delete it, then uninstall the Pushover app. It was a one-time purchase, so there's no subscription to cancel.
5. **Optional, in AWS** (Render-era leftovers that nothing uses now):
   - IAM → Users → `prominder-backend` → Delete. Its access key lived on Render.
   - Cognito → User pools → `prominder` → Delete. It was the web app's login.
   - The old Supabase project, if it still exists. Its data was copied to DynamoDB long ago.
   Leave the DynamoDB table `prominder`, the SES address, the function `prominder-api`, the roles
   `prominder-lambda` and `prominder-github-deploy`, the EventBridge rule and the budget alone: they're the backend.

**Done when:** the phone still backs up daily (Diagnostics → *Last backup*), and Render, cron-job.org and Pushover
are gone.

## What's new

### The backup: daily, plus "when convenient" (`sync/SyncWorker.kt`)
- **Once a day** on any network, as before. It's the backup itself and the "phone is alive" signal the Lambda's
  daily check reads. Nothing changed for a day: an empty upload anyway.
- **When convenient:** leaving the app with changes not backed up yet queues one upload that runs only on Wi-Fi
  (any unmetered network) with the battery not low, whenever Android finds that convenient. Leaving again meanwhile
  doesn't queue a second one. A lost or reset phone loses minutes, not a day, without using mobile data.
- **No more upload after every change.** That was only there while the app was being built, in case a new version
  had to rebuild the phone's database from the backup. The database has migrations instead, so it isn't needed.
- *Back up now* in Diagnostics still uploads right away, on any network.
- The handover screen says the new rhythm ("once a day, and when you leave the app on Wi-Fi").

### The backend's address (`app/build.gradle.kts`, `.github/workflows/android.yml`)
- The app had Render's address built in as the default. It now has none. The release job checks the `API_URL`
  variable first, with a clear error, and the release build refuses an empty address or Render's. So an app can
  never ship pointing at a server that's gone.

### Removed: the web app and the server (they stay in the repository's history)
- **`web/`** (the installable web app), **`render.yaml`** (Render's blueprint) and **`db/`** (the old Supabase
  schema, only used by the one-off migration to DynamoDB).
- **`backend/app/`**: the FastAPI server and everything only it used. That's the routes (`main.py`), the tick
  (`scheduler.py`), the coach, agent, tools, tool loop and context, the logic and the Shabbat calendar, the task,
  chat, session, preference and settings storage, the button actions, web push and Pushover, and the signed button
  tokens. All of it runs on the phone now, ported to Kotlin in Phases C to E.
- **What's left** is what the Lambda runs: `lambda_app.py`, `auth.py` (Google sign-in only: no more Cognito or cron
  secret), `sync.py` (the stored rows' definitions moved in, since it was the only thing using them), `alive.py`,
  `llm_proxy.py`, `llm.py` (the daily cap lives in `llm_proxy.py` only, counted in DynamoDB), `db.py` (only the
  operations still used), `config.py` (only the function's own settings) and `prompts/` (the app ships these).
- **Scripts**: `aws_setup.py` was folded into `aws_lambda_setup.py`. That one script now also creates the table and
  the SES address if they're missing, and the $1 budget alert, and makes up `ACTION_SECRET` on a first run (it keeps
  the existing one, so the phone stays logged in). The Cognito pool and the IAM user for Render are gone from it. The
  one-off and Render-era scripts were removed: `migrate_from_postgres`, `backfill_next_actions`, `simulate_tick`,
  `gen_secrets` and `make_android_sync_sample`. `metrics.py` stays and reads through the backup.
- `backend/requirements.txt` is now the Lambda's dependencies plus boto3 (no uvicorn, web push or hdate).

### Docs
- **README**: rewritten around the phone and the Lambda. That covers the diagram, each tick (the phone's exact
  alarms), *How loud* (the phone's channels and the full-screen alarm instead of Pushover), security, setup (the
  Lambda script, the app, the phone), the Lambda's settings and the project layout. Links to the logic point to the
  Kotlin code.
- **Privacy policy**: the login is Google sign-in, not a Cognito email code, and the alive email is mentioned. The
  Play form texts no longer mention Supabase. The policy's public URL is the file on `main`, so it updates on merge.
- `backend/.env.example` lists only the Lambda's settings. `android/README.md`: `API_URL` is required, and Google
  sign-in's values go to the Lambda, not Render.
- The ⚙ Settings screen no longer says reminders "come back in the next updates".

### Tests
- Android: **312** (309 before), plus lint. `SyncSchedulerTest` (3): the daily run on any network every 24 hours;
  leaving the app queues one Wi-Fi-only, battery-not-low upload however often you leave; a change to your data
  doesn't schedule an upload by itself.
- Backend: **65**, the Lambda's tests only (`test_lambda`, `test_llm_proxy`, `test_sync`, `test_auth_google`,
  `test_metrics`, `test_reasoning_config`). The tests of the server's logic were ported to Kotlin one for one in
  Phases C to E and live on there. `test_sync.py` now works from the Android tests' sample
  (`restore-sample.json`), so both sides check the same rows. It covers restore, byte-for-byte round trips, the
  no-Scan rule, step lists, message links, counters, deletes, bad rows, the size limit and the handover. New: a
  Cognito-style token is refused, and an empty OpenRouter key counts as "not set up".

## Decisions and deviations from the plan

1. **"When convenient" means Wi-Fi with the battery not low**, as in the plan's §4 ("when the app goes to the
   background on Wi-Fi"). The daily run takes any network, so the backup and the alive signal never depend on Wi-Fi.
2. **`db/` was removed too.** The plan named only `web/` and the backend. The Postgres schema was only read by the
   migration script, which went with it.
3. **`metrics.py` stays.** The success measures are still worth running, and reading them through the backup costs
   ten lines.
4. **`ACTION_SECRET`'s name stays.** It's the name on the Lambda, and the login key is derived from it in the same
   way, so the phone stays logged in.
5. **The release refuses Render's address** rather than quietly falling back to anything. A missing `API_URL` is a
   red build with a sentence that says what to set, not an app that can't log in.
6. **Render, cron-job.org, Pushover and the AWS leftovers are yours to delete** (above): they're accounts, not code.
