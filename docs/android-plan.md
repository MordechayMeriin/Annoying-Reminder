# Android app: the plan

A plan for turning Annoying Reminder from a web app (PWA) plus a server into a native Android app,
written for someone new to mobile development. Concepts are explained the first time they come up, and
there's a [glossary](#glossary) at the end.

**Short version:** the phone becomes the brain. A Kotlin app keeps everything in a local database and
decides on its own when to remind you, using the phone's alarm clock instead of a server that checks
every 5 minutes. Supabase keeps the login and becomes the backup, synced daily. The only thing the
phone still needs the internet for is the LLM, which goes through a small AWS Lambda function so your
OpenRouter key never sits on the phone. The app is installed from the Play Store (internal testing
track), runs on your Galaxy S9 (Android 10), and is built and tested in the cloud, so your laptop needs
nothing but a browser. Render, cron-job.org, browser push and Pushover all go away.

Decisions so far: Kotlin; AWS Lambda for the server part; installed through Google Play; native alarms
replace Pushover; contacts and addresses later, maybe; no Android Studio; Galaxy S9.

---

## 1. Where we are today

```
 Phone (Chrome PWA)            Render (FastAPI, Python)               Supabase
 ┌───────────────┐  HTTPS   ┌──────────────────────────────┐  SQL  ┌──────────┐
 │ web/app.js    │ ───────▶ │ agent + 12 tools (LLM chat)  │ ────▶ │ Postgres │
 │ (UI only,     │ ◀─push── │ /tick: logic → coach → push  │       └──────────┘
 │  no data)     │          │ sessions, What now?, settings│
 └───────────────┘          └──────────────────────────────┘
                                ▲ every 5 min (cron-job.org)    └──▶ Pushover
```

The phone holds no data and makes no decisions. Every reminder depends on four services being up at
the same moment: cron-job.org pings Render, Render (which sleeps on the free plan) wakes up, reads
Supabase, calls the LLM, and pushes through Chrome or Pushover. No internet on the phone, no reminder.

What's worth keeping as it is:
- **The design.** "Code decides when, which playbook and which buttons; the LLM writes the words",
  the guardrails, gentler pressure, the template fallback. None of that changes.
- **The prompts** (`backend/app/prompts/*.md`). They're plain text; the phone uses the same files.
- **The tests** (277 of them, ~4,400 lines). They're the most precise description of how the app
  behaves, so they become the checklist for the Android version.

## 2. Where we're going

```
 Galaxy S9 (the brain)                                   AWS Lambda (Python)          Supabase
 ┌──────────────────────────────────────────────┐      ┌──────────────────────┐    ┌──────────┐
 │ UI (Jetpack Compose): chat, Today's 3, tasks │daily │ /sync  backup/restore│───▶│ Postgres │
 │ Room DB (SQLite): tasks, messages, sessions… │─────▶│ /llm   holds the key │    │ Auth     │
 │ logic + scheduler (ported from Python)       │      │ daily: "is the phone │    └──────────┘
 │ AlarmManager: wakes the app at the right time│─LLM─▶│  still alive?"       │──▶ OpenRouter
 │ notifications: normal / urgent / alarm       │      └──────────────────────┘
 └──────────────────────────────────────────────┘
```

| Today | On Android |
|---|---|
| Postgres on Supabase is the only copy | **Room** (a local SQLite database) is the live copy; Supabase is the backup |
| cron-job.org pings `/tick` every 5 min | **AlarmManager** wakes the app exactly when the next thing is due |
| Render runs the logic in Python | The logic runs on the phone, in Kotlin |
| Browser push + Pushover | Native notifications, with a real alarm screen for the loudest level |
| LLM key on Render | LLM key in an **AWS Lambda** function (a small function that runs on demand) |
| No internet → nothing works | No internet → everything works, with template messages instead of LLM ones |

### Why the phone has to be the brain (and not just a cache)

A local database that syncs daily only works if the phone is also the one deciding when to remind
you. If the server kept deciding, it would be reading data up to a day old: you'd mark the dentist done
at 10:00 and the server would still nag you about it at 14:00. With the phone as the only writer, the
server never needs fresh data, daily sync is plenty, and there are no conflicts to resolve.

### Why Kotlin, and what the other options are

The question that decides it: **what is the hardest part of this app?** It isn't the screens. It's
"the reminder arrives on time, loudly, with buttons, even offline, with the screen off, on a Samsung
that likes to put apps to sleep". That's background work: alarms that wake the app, code that runs
with no screen open, notification channels, a full-screen alarm, a restart after reboot, a widget.
Each option handles that part differently.

| Option | Language | How it does the hard part | Fits us? |
|---|---|---|---|
| **Kotlin + Jetpack Compose** (native) | Kotlin | Directly: alarms, background jobs, notifications, widgets and tiles are the platform's own APIs, with Google's docs and samples | **Chosen** |
| **React Native / Expo** | TypeScript (like `app.js`) | Through community plugins; code that runs when an alarm fires with no screen open ("headless JS") is the fiddliest part | Real advantage: one language you know, and live reload on the phone. Weak exactly where this app needs to be strong |
| **Flutter** | Dart (new to you) | Through plugins, same issue | Its strength is iOS + Android from one codebase, which we don't need |
| **Python inside the app** (Chaquopy) | Python + Kotlin | Kotlin app with a Python interpreter embedded, so `logic.py` could be reused | Tempting, but `tasks.py` and friends are Postgres SQL and get rewritten anyway; it starts a Python interpreter on every alarm (slow on an S9), adds ~25 MB, and you'd debug across two languages without Android Studio |
| **Capacitor** (wrap the web app) | JavaScript | The current web UI in an app shell, with plugins for the rest | Fastest first screen, but the logic still has no home on the phone |
| Kotlin Multiplatform, .NET MAUI | Kotlin, C# | Share code with iOS / desktop | Nothing to share with |

Kotlin itself is a small step from Python and TypeScript: types are inferred, data classes look like
the `@dataclass`es in `logic.py`, and the compiler catches `null` mistakes before they reach the phone.
A bonus: Shabbat and holiday times come from **KosherJava Zmanim**, a mature Java library Kotlin uses
directly. And since I write most of the code, what matters most for you is that it's readable, which
Kotlin is.

### Why AWS Lambda (and not the key in the app)

An app file can be unpacked, so a key inside it can be read by anyone who gets the file. So a small
server part stays: it holds the OpenRouter key, checks that the request comes from you (your Supabase
login token), enforces the daily LLM cap and the rate limit, and forwards the call with the model
fallback list. The same function handles sync.

Supabase Edge Functions and AWS Lambda both do this well. Lambda wins for us:
- **It runs Python**, so `llm.py` (OpenRouter, model fallback, timeouts) and parts of `config.py` move
  over almost unchanged. Edge Functions are TypeScript only.
- **Up to 15 minutes per call** (Edge Functions stop at about 2.5), so a slow model never cuts off.
- **A scheduled job** (EventBridge) for the "is the phone still alive?" check (see Phase F).
- **Cost: nothing.** The free tier is 1 million calls a month, forever; we'll use a few hundred a day.
  We still set a $1 budget alert, so a mistake can't surprise you.

What it costs in effort: a bit more setup than an Edge Function (a function URL, permissions, logs in
CloudWatch). It's deployed by GitHub Actions, with no AWS keys stored in GitHub (OIDC).

The database stays closed to the public API exactly as today (RLS on, no policies): only the Lambda,
with the connection string, touches it.

## 3. Your phone: Galaxy S9

- Its last Android version is **Android 10** (check: Settings → About phone → Software information).
  The app supports Android 10 and up, so a future phone works too.
- **Good news:** on Android 10, exact alarms, full-screen alarms and notifications need no special
  permission screens. Newer Android versions ask for each; the app handles that too, because Google
  Play requires apps to be built for current Android versions.
- **Watch out: Samsung puts apps to sleep.** The app has to be on Samsung's list of apps that are never
  put to sleep (roughly Settings → Device care → Battery → App power management) and have battery
  optimization off. The app's first-run checklist opens each screen for you.
- Not available on Android 10, so not planned: Live Updates (Android 16's progress notifications) and
  a few newer widget features.

## 4. Key building blocks, explained

**The local database (Room).** Room is a layer over SQLite, the database built into every Android
phone. You write SQL (like `tasks.py` does today) and Room checks it when the app is built. Two
differences from Postgres: SQLite has no arrays (`messages.task_ids`) or `jsonb` (`chips`), so those
are stored as JSON text; and dates are stored as numbers. Schema changes are **Room migrations**, the
same idea as `db/migrations/`.

**Sync (WorkManager).** WorkManager runs background jobs that must eventually happen, even if the app is
closed or the phone restarts. A daily job, set to "only with internet", sends every row changed since
the last sync to `/sync`. The phone's copy always wins. Two small additions make that work:
- every table gets an `updated_at` column, so the phone knows what changed since the last sync;
- deleted rows are remembered locally until the next sync, so Supabase deletes them too.

The same job also runs, cheaply, when the app goes to the background on Wi-Fi. A lost phone then loses
minutes, not a day. **Restore** (new phone, reinstall) is the reverse: download everything into an
empty database.

**The scheduler (AlarmManager).** Today the server checks every 5 minutes whether anything is due.
A phone that wakes up every 5 minutes drains the battery, and Android's **Doze** mode (deep sleep when
the phone is idle) would delay it anyway. Instead, after every change, the phone works out **the next
moment something could happen** (a `remind_at`, a meeting, a hard stop, the morning plan, the end of a
session, the end of Shabbat…) and sets one **exact alarm** for it. When it fires, the app runs the
same tick logic as `scheduler.plan_tick`, sends what's due, and sets the next alarm. Exact alarms are
what alarm-clock apps use, and they get through Doze. Three safety nets:
- alarms are wiped when the phone restarts, so a **boot receiver** sets them again;
- a time-zone or clock change also re-plans;
- a WorkManager job every 15 minutes runs the tick anyway, in case Samsung's battery saver interfered.

**The LLM at alarm time.** When a check-in is due, the phone asks the LLM to write it, with a short
timeout. Offline, slow, or out of quota: the template is sent instead, exactly as the server's fallback
does today. A reminder is never lost because of the network.

**Notifications.** Android groups notifications into **channels**, and you control each one in system
settings (sound, vibration, whether it may break through Do Not Disturb). Three channels, the same levels
as today:

| Channel | Today | On Android |
|---|---|---|
| Normal | browser push / Pushover normal | normal sound |
| Urgent | Pushover urgent | long sound, high priority, allowed through Do Not Disturb (you allow it once) |
| Alarm | Pushover emergency, repeats every 2 min | a **full-screen alarm screen**, like the clock app's, that rings until you tap ✓ / ⏰ / Open, and never runs into quiet hours or Shabbat |

Notifications get up to three buttons (▶ Start 5 min · ✓ Done · **Reply**; Reply lets you type or
dictate an answer into the chat without opening the app). They also show on a paired smartwatch.

**Focus sessions.** A running timer is an ongoing notification with a live countdown (Android draws
the countdown itself, so nothing has to keep running in the background), and the end is an exact
alarm. So the end check-in arrives on time, not "within 5 minutes".

## 5. Google Play: doing it properly

Installing through Google Play is a given, so the plan is built around it.

**Which track.** Play has several "tracks" an app can be released to. We use **internal testing**:
up to 100 testers you name by email (just you), installs and updates through the Play Store like any
app, and releases available within minutes. Two things we avoid by staying there:
- personal developer accounts must run a 14-day closed test with 12+ testers before a *public* release.
  We never need a public release;
- the full review that public releases get. (Play's policies still apply; see the table below.)

For quick try-outs there's also **internal app sharing**: upload a build, get a link, install it from
the Play Store on your phone. It's switched on once in the Play Store app (Settings → About → tap
"Play Store version" seven times, then General → Internal app sharing).

**Signing.** Every Android app is signed, and an update must be signed by the same key. With **Play App
Signing**, Google keeps the real signing key and we keep an "upload key" (stored as a GitHub secret). If
the upload key is ever lost, Google can reset it, so nothing is lost for good.

**One-time setup (you):** a Google Play developer account ($25 once; identity verification can take a
few days, so it's worth starting now), and the store forms: a short privacy policy page, the data safety
form ("the data is yours, stored in your Supabase, sent to an LLM to write messages"), and content rating.
I'll draft the texts.

**Policy-sensitive features, and the Play-friendly way to do each:**

| Need | What we do |
|---|---|
| Exact alarms | Ask for "Alarms & reminders" (`SCHEDULE_EXACT_ALARM`), which needs no Play declaration. On Android 10 it's automatic; on Android 14+ the user switches it on. Without it, alarms fall back to "within a few minutes". |
| Full-screen alarm | Declared to Play as alarm functionality (a form in the Play Console). On Android 10 it's automatic. If Play ever says no, the fallback is an "insistent" notification whose sound loops until you act, which is nearly the same. |
| Battery exemption | Play allows the one-tap "stop optimizing battery" dialog only for some kinds of apps, so the app opens the right settings page and explains, plus Samsung's "never sleeping" list. |
| Focus timer | No foreground service (Android 14+ makes apps justify those to Play): a countdown notification plus an exact alarm does the job. |
| Background location, usage tracking | Not used: both mean heavy review. (So no location reminders and no "scroll check".) |

## 6. Developing without Android Studio

Android Studio is the usual tool, and it needs a strong computer. We don't need it:

- **I build and test in the cloud session.** I checked: this environment can download the Android build
  tools, so I can compile the app and run its tests before you ever see a change.
- **Most tests need no phone.** The ported logic tests (most of the 277) run on a normal computer.
- **Screens as pictures.** Screenshot tests render each screen to an image without an emulator (a
  virtual phone), so a change to the UI comes with "here's what it looks like" before you install it.
- **Newer Android versions** are checked on an emulator in GitHub Actions, since the S9 can't show them.
- **Releases:** GitHub Actions builds the app bundle and uploads it to the internal testing track. Your
  phone updates from the Play Store.
- **When something goes wrong on the phone:** a Diagnostics screen in the app shows its own log (ticks,
  alarms set, syncs, LLM errors) with a Share button, so you can paste it to me. Crashes also show up in
  the Play Console automatically ("Android vitals").

Your laptop needs a browser and nothing else.

## 7. Phases

Each phase ends with something you can install and use. The web app keeps working, untouched, until
the cutover in Phase F, so you're never without reminders.

**Before the cutover, the phone never sends reminders on its own, and anything you do in it is
practice.** The server is still the one sending you real reminders. If the phone sent its own too,
you'd get everything twice, and the two would each keep a separate chat that disagree from then on. So
until Phase F the phone works on a copy of your data that is refreshed from Supabase (overwriting
practice changes), and you can chat with it, tap buttons and run sessions as much as you like.

### Phase A: hello, Android (and Google Play)
- You: create the Play developer account; switch on internal app sharing on the phone.
- An `android/` folder in this repo with a Kotlin + Compose project, supporting Android 10 and up.
- GitHub Actions: build, run the tests, render screenshots, upload to internal testing.
- Login with your Supabase email code; notification channels; the first-run checklist (including
  Samsung's sleeping apps); the Diagnostics screen; two test buttons: "notify me now" and "alarm in
  1 minute".
- Privacy policy page and store forms.

*Done when:* the app comes from the Play Store, and a test alarm rings on time after the phone has sat
untouched with the screen off for an hour (that's the Doze test).

### Phase B: your data on the phone
- Room schema mirroring `db/schema.sql`.
- Supabase: migration `010` adds `updated_at` to every table. Additive: the current server keeps working.
- AWS: the Lambda with `/sync` (download only for now), deployed from GitHub Actions; the $1 budget alert.
- Screens: the task list, task details (description, steps, history), the chat history, Today's 3, settings.

*Done when:* the app shows your real tasks and chat in airplane mode.

### Phase C: the brain, in shadow mode
- Port `logic.py` (pure functions, ~1,000 lines) and its tests first. The tests are the spec: a test that
  passes in Python must pass in Kotlin.
- Port the Shabbat and holiday logic to KosherJava Zmanim, checked against `test_shabbat.py`.
- Port the tick: `scheduler.py`, the guardrails, alert levels, gentler pressure, the template messages.
- The scheduler (exact alarm and safety nets) and notification buttons.
- **Shadow mode:** the phone downloads fresh data every 15 minutes, works out what it *would* send and
  when (which tasks, how loud, which buttons), and logs it on the Diagnostics screen next to what the
  server actually sent. We compare **decisions**, not messages, because decisions are code and must
  match exactly, while the LLM's wording is different every time anyway. Wording gets tested for real
  in Phase D.

*Done when:* for a week, the phone's decisions match what the server sent.

### Phase D: chat and the coach
- `/llm` in the Lambda: the key, model fallback, daily cap, rate limit, timeouts.
- Port the agent loop (`agent.py`, `llm_loop.py`), the 12 tools (`tools.py`, now writing to Room), the
  context builder and prompt rendering. The prompt files ship with the app; a copy downloaded at sync can
  override them, so you can still change a prompt without a new app release.
- Chat screen: chips, *Sending… / ✓ Sent*, typing dots, the tool status lines, dictation (Android's
  own speech recognizer, Hebrew included), share-to-app, the "New task" long-press shortcut.
- The coach (morning plan, check-ins, evening review, Sunday, re-entry) writes through `/llm`.
- Reply from a notification.

*Done when:* practice conversations on the phone feel like the web app's.

### Phase E: focus sessions and alarms
- Focus sessions: the countdown notification, the full-screen ring, "where did you stop?".
- The full-screen alarm and its repeat rules.
- What now?, Low energy, Something else (pure code, already ported in Phase C).

*Done when:* everything in the README works on the phone.

### Phase F: cutover
1. Final download from Supabase, so the phone starts from the latest state.
2. Turn off the cron-job.org job. From this moment the phone is the brain and sends real reminders.
3. `/sync` switches direction: the phone uploads, daily plus the cheap "when convenient" run.
4. **"Is the phone still alive?"**: once a day, the Lambda checks when the phone last synced. If it's
   been over two days, you get an email. Now that the phone is the brain, nobody else would notice if
   it stopped.
5. A week later, if all is well: delete the Render services and the cron-job.org account, remove
   Pushover and browser push from the code, and decide what to do with the web app (see
   [Open questions](#9-open-questions)).

**Rollback:** sync once from the phone, turn cron-job.org back on, and the server picks up from there.
The Python code stays in the repo until you're sure.

### Phase G: things only a phone can do
One at a time, roughly in order of value for effort:

1. **Home-screen widget:** Today's 3 with ▶ Start, and a What now? button. The plan without opening the app.
2. **Quick capture:** a Quick Settings tile (the pull-down panel) that opens straight into dictation. A
   thought goes in within two seconds, before it's gone.
3. **Catch me when I'm on my phone:** a normal-level check-in that's due while the screen is off waits a
   few minutes (up to a limit) for the screen to come on. Fewer nudges buzz unseen in a pocket, so fewer
   are counted as "ignored", which matters because ignored check-ins feed the avoidance logic.
4. **Calendar, read-only** (optional): meetings from the phone's calendar show up as meetings
   automatically, with their reminders and digests. It uses Android's local calendar store, so it works
   offline and needs no Google API setup.
5. **Quiet focus:** optionally turn on Do Not Disturb during a focus session (except the app's own alarms
   and hard stops), and turn it off when it ends.
6. **Distinct vibrations** per level, so you know "urgent" from "normal" without looking.
7. **Driving:** hold check-ins while you're driving.

**Later, maybe:** one-tap actions from contacts and addresses (▶ Start on "Call mom" dials her; an
address gets Navigate).

## 8. Risks and how we handle them

| Risk | Handling |
|---|---|
| **The port is big:** ~5,000 lines of Python plus ~1,750 of UI. Bugs creep in. | Tests ported first, logic before UI, a week of shadow mode before the cutover. |
| Samsung's battery saver stops the app | Exact alarms, the 15-minute safety net, the first-run checklist, a "last tick" time on the Diagnostics screen, and the daily "is the phone alive?" email. |
| Play rejects a permission | Each policy-sensitive feature has a fallback (see [Google Play](#5-google-play-doing-it-properly)). |
| Phone lost or reset between syncs | The "when convenient" sync on top of the daily one. |
| LLM slow or unavailable at alarm time | Short timeout and the template fallback, as today. |
| Time zones, DST, Shabbat edge cases | `java.time` handles zones properly; the Python tests for these are ported one for one. |
| An old phone (2018) | A native app is light. If the S9 is replaced, the app works on the new phone as it is. |
| You're new to Android | Small phases, each installable. Each PR explains what's new and why, like the phase docs do now. |

## 9. Open questions
1. **After the cutover, what happens to the web app?** Retire it, or keep a read-only page that shows the
   Supabase backup (useful on a computer, but up to a day old)?
2. **Google Play account:** do you already have one? Personal or organization?
3. **AWS:** which account and region? This session has an AWS connection, so when we get to Phase B I
   can deploy the Lambda for you (after you confirm), or GitHub Actions can.

## Glossary
- **App bundle (AAB)**: the file uploaded to Google Play; Play turns it into the app your phone installs.
  **APK** is the older, directly installable format.
- **Track** (internal testing, closed, open, production): who can install a release from Play.
- **Signing key / upload key**: prove an update comes from the same developer.
- **Gradle**: the build tool (like `pip` plus a build script). Builds the app from the Kotlin source.
- **Activity**: a screen the system can open (the main app, the full-screen alarm). **Compose**: the way
  the UI is written, as Kotlin functions (similar to React components).
- **Room**: the local SQLite database layer. **DAO**: the class that holds a table's queries.
- **WorkManager**: background jobs that must eventually run (sync, the safety net).
- **AlarmManager / exact alarm**: wakes the app at an exact time, even in deep sleep.
- **Doze**: Android's deep sleep for idle phones, which delays most background work.
- **Notification channel**: a category of notifications with its own sound and Do Not Disturb setting.
- **Broadcast receiver**: code that runs when the system announces something (phone restarted, time
  zone changed).
- **AWS Lambda**: a function hosted by AWS that runs only when called. **Function URL**: its web address.
  **EventBridge**: AWS's scheduler, which runs it on a timetable.
- **Emulator**: a virtual Android phone. **Screenshot test**: renders a screen to an image, no phone needed.
