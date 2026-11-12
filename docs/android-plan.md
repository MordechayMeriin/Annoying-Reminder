# Android app: the plan

A plan for turning Annoying Reminder from a web app (PWA) plus a server into a native Android app,
written for someone new to mobile development. Concepts are explained the first time they come up, and
there's a [glossary](#glossary) at the end.

**Short version:** the phone becomes the brain. A Kotlin app keeps everything in a local database and
decides on its own when to remind you, using the phone's alarm clock instead of a server that checks
every 5 minutes. Supabase becomes the backup, synced daily. The only thing the phone still needs the
internet for is the LLM, which goes through a tiny proxy so your OpenRouter key never sits on the phone.
Render, cron-job.org, browser push and (optionally) Pushover all go away.

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
 Android phone (the brain)                                       Supabase
 ┌──────────────────────────────────────────────┐             ┌────────────────────────┐
 │ UI (Jetpack Compose): chat, Today's 3, tasks │   daily     │ Edge Function          │
 │ Room DB (SQLite): tasks, messages, sessions… │ ──sync────▶ │  /sync  (backup/restore)│──▶ Postgres
 │ logic + scheduler (ported from Python)       │             │  /llm   (holds the key) │──▶ OpenRouter
 │ AlarmManager: wakes the app at the right time│ ──LLM call─▶│                        │
 │ notifications: normal / urgent / alarm       │             └────────────────────────┘
 └──────────────────────────────────────────────┘
```

| Today | On Android |
|---|---|
| Postgres on Supabase is the only copy | **Room** (a local SQLite database) is the live copy; Supabase is the backup |
| cron-job.org pings `/tick` every 5 min | **AlarmManager** wakes the app exactly when the next thing is due |
| Render runs the logic in Python | The logic runs on the phone, in Kotlin |
| Browser push + Pushover | Native notifications, with a real alarm screen for the loudest level |
| LLM key on Render | LLM key in a Supabase **Edge Function** (a small serverless function) |
| No internet → nothing works | No internet → everything works, with template messages instead of LLM ones |

### Why the phone has to be the brain (and not just a cache)

You suggested a local database that syncs daily. That only works if the phone is also the one
deciding when to remind you. If the server kept deciding, it would be reading data up to a day old:
you'd mark the dentist done at 10:00 and the server would still nag you about it at 14:00.

So the choice is between:
- **Phone is the brain** (recommended): the phone is the only writer, the server never needs fresh data,
  and daily sync is plenty, because Supabase is only a backup.
- **Server stays the brain**: the local database is just a cache and must sync within seconds. That's
  more sync work for less benefit, and reminders still depend on Render being awake.

Going with the first. The cost is porting the Python logic to Kotlin (see [Risks](#7-risks-and-how-we-handle-them)).

### Why Kotlin + Jetpack Compose

The options for building an Android app, briefly:

| Option | What it is | Verdict |
|---|---|---|
| **Kotlin + Jetpack Compose** | Google's own language and UI toolkit | **Chosen.** Alarms, notifications, widgets, background work and calendars are first-class. All of Google's docs and samples use it. |
| Flutter / React Native | One codebase for Android and iOS | Their advantage is iOS, which we don't need. Every deep phone feature goes through a plugin. |
| Capacitor (wrap the web app) | The current web UI inside an app shell | Fastest start, but the logic still needs a home, and alarms/widgets/tiles become plugins. |

Kotlin is close to Python and TypeScript in feel: types are inferred, there are data classes (like the
`@dataclass`es in `logic.py`), and `null` is handled by the compiler. A useful bonus: the Shabbat and
holiday times come from **KosherJava Zmanim**, a mature Java library that Kotlin uses directly.

### Why an Edge Function for the LLM (and not the key in the app)

An APK can be unpacked, so a key inside it can be read by anyone who gets the file. A ~100-line Supabase
Edge Function holds the key, checks that it's you (your Supabase login), enforces the daily cap and
the rate limit, and forwards the call to OpenRouter with the model fallback list. Edge Functions don't
"sleep" like Render's free plan, so a chat reply doesn't wait a minute for a cold server.

The same function handles sync, so the database stays closed to the public API exactly as today (RLS on,
no policies). No new security model to learn.

## 3. Key building blocks, explained

**The local database (Room).** Room is a layer over SQLite, the database built into every Android
phone. You write SQL (like `tasks.py` does today) and Room checks it at compile time. Two differences
from Postgres to handle: SQLite has no arrays (`messages.task_ids`) or `jsonb` (`chips`), so those are
stored as JSON text; and dates are stored as numbers (epoch milliseconds). Schema changes are
**Room migrations**, the same idea as `db/migrations/`.

**Sync (WorkManager).** WorkManager runs background jobs that must eventually happen, even if the app is
closed or the phone restarts. A daily job, set to "only with internet", sends every row changed since
the last sync to `/sync`. Because the phone is the only writer, there are no conflicts to resolve: the
phone's copy always wins. Two small additions make that work:
- every table gets an `updated_at` column, so the phone knows what changed since the last sync;
- deleted rows are remembered locally until the next sync, so Supabase deletes them too.

The same job also runs, cheaply, when the app goes to the background on Wi-Fi. That's optional, but it
means a lost phone loses minutes, not a day. **Restore** (new phone, reinstall) is the reverse: download
everything from `/sync` into an empty database.

**The scheduler (AlarmManager).** Today the server checks every 5 minutes whether anything is due.
A phone that wakes up every 5 minutes drains the battery, and Android's **Doze** mode (deep sleep when
the phone is idle) would delay it anyway. Instead, after every change, the phone works out **the next
moment something could happen** (a `remind_at`, a meeting, a hard stop, the morning plan, the end of a
session, the end of Shabbat…) and sets one **exact alarm** for it. When it fires, the app runs the
same tick logic as `scheduler.plan_tick`, sends what's due, and sets the next alarm. Exact alarms are
what alarm-clock apps use and they get through Doze. Three safety nets:
- alarms are wiped when the phone restarts, so a **boot receiver** sets them again;
- a time-zone or clock change also re-plans;
- a WorkManager job every 15 minutes runs the tick anyway, in case a phone maker's battery saver killed
  something (Samsung and Xiaomi are known for it).

**The LLM at alarm time.** When a check-in is due, the phone asks the LLM to write it, with a short
timeout. Offline, slow, or out of quota: the template is sent instead, exactly as the server's fallback
does today. A reminder is never lost because of the network.

**Notifications.** Android groups notifications into **channels**, and you control each one in system
settings (sound, vibration, whether it may break through Do Not Disturb). We create three, matching the
current levels:

| Channel | Today | On Android |
|---|---|---|
| Normal | browser push / Pushover normal | normal sound |
| Urgent | Pushover urgent | long sound, high priority, may override Do Not Disturb (you allow it once) |
| Alarm | Pushover emergency, repeats every 2 min | a **full-screen alarm screen**, like the clock app's, that rings until you tap ✓ / ⏰ / Open |

That's what Pushover gives today, built in, so Pushover becomes optional.
Notifications get up to three buttons (▶ Start 5 min · ✓ Done · **Reply**, where Reply lets you type or
dictate an answer into the chat without opening the app). They show on a paired smartwatch for free.

**Focus sessions (foreground service).** A running timer becomes an **ongoing notification** with a
live countdown, which Android won't kill while it runs (that's what a foreground service is for). The
end is an exact alarm, so the end check-in arrives on time, not "within 5 minutes".

**Permissions.** Android asks the user for sensitive permissions at runtime. We'll need: notifications,
exact alarms ("Alarms & reminders"), full-screen alarms, ignoring battery optimization (recommended),
and later, optionally, microphone, calendar and location. The app shows a short setup checklist on first
run, with a button for each, instead of a wall of popups.

## 4. Phases

Each phase ends with something you can install and use. The web app keeps working, untouched, until
the cutover in Phase F, so you're never without a reminder app while this is being built.

### Phase A: hello, Android (learning the toolchain)
- An `android/` folder in this repo with a Kotlin + Compose project.
- **GitHub Actions** builds an APK (the installable app file) on every push. You download it on the
  phone and install it; no computer needed. Later, the free **Obtainium** app can update it
  automatically from GitHub releases.
- A **signing key** (a file that proves updates come from you; Android refuses an update signed with a
  different key). It's stored as a GitHub secret, and you keep a backup copy somewhere safe.
- Login with your Supabase email code, a first screen, notification permission, and two test buttons:
  "notify me now" and "alarm in 1 minute".
- Recommended but optional: Android Studio on a computer, for the emulator (a virtual phone) and
  Logcat (the app's live log).

*Done when:* you install your own app and it buzzes on time with the screen off.

### Phase B: your data on the phone (read-only)
- Room schema mirroring `db/schema.sql`.
- Supabase: migration `010` adds `updated_at` to every table; the Edge Function with `/sync` (pull only).
- One-time import, then a pull every few hours. The server is still the brain, so the phone only reads.
- Screens: the task list, task details (description, steps, history), the chat history, Today's 3.
- Settings screen reading `app_settings`.

*Done when:* the app shows your real tasks and chat in airplane mode.

### Phase C: the brain, in shadow mode
- Port `logic.py` (pure functions, ~1,000 lines) and its tests first. The tests are the spec: a test
  that passes in Python must pass in Kotlin.
- Port the Shabbat and holiday logic to KosherJava Zmanim, checked against `test_shabbat.py`.
- Port the tick: `scheduler.py`, the guardrails, alert levels, gentler pressure, the template messages.
- The scheduler (exact alarm + safety nets), the three notification channels, notification buttons.
- **Shadow mode:** the phone computes what it *would* send and logs it to a debug screen without
  notifying, while the server keeps working. Comparing the two for a few days shows where the port
  differs, before anything depends on it.

*Done when:* for a week, the phone's decisions match what the server actually sent.

### Phase D: chat and the coach
- `/llm` in the Edge Function: key, model fallback, daily cap, rate limit, timeouts.
- Port the agent loop (`agent.py`, `llm_loop.py`), the 12 tools (`tools.py`, now writing to Room), the
  context builder and prompt rendering. The prompt files are bundled with the app; a copy downloaded at
  sync can override them, so you can still tweak a prompt without a new app version.
- Chat screen: chips, *Sending… / ✓ Sent*, typing dots, the tool status lines, dictation (Android's
  own speech recognizer, Hebrew included), share-to-app, the "New task" long-press shortcut.
- The coach (morning plan, check-ins, evening review, Sunday, re-entry) writes its messages through `/llm`.
- Reply from a notification.

### Phase E: focus sessions and alarms
- Focus sessions as a foreground service, the full-screen ring, "where did you stop?".
- The full-screen alarm screen and its repeat rules (never into quiet hours or Shabbat).
- What now?, Low energy, Something else (pure code, already ported in Phase C).

*Done when:* everything in the README works on the phone, still in shadow mode for the server's data.

### Phase F: cutover
1. Final import from Supabase, so the phone starts from the latest state.
2. Turn off the cron-job.org job. From this moment the phone is the brain.
3. `/sync` switches to push: daily, plus the cheap "when convenient" run.
4. A week later, if all is well: delete the Render services, cancel Pushover if you don't miss it, and
   decide what to do with the web app (see [Open questions](#8-open-questions)).

**Rollback:** sync once from the phone, turn cron-job.org back on, and the server picks up from there.
Keep the Python code in the repo until you're sure; it costs nothing.

### Phase G: things only a phone can do
One at a time, in roughly this order of value for effort. Each is a small, separate piece of work.

1. **Home-screen widget:** Today's 3 with ▶ Start, and a What now? button. The plan without opening the app.
2. **Quick capture:** a Quick Settings tile (the pull-down panel) and a lock-screen shortcut that open
   straight into dictation. A thought goes in within two seconds, before it's gone.
3. **One-tap actions on tasks:** a phone number gets a Call button, an address Navigate, a link Open.
   ▶ Start on "Call mom" dials her. The first step *is* the tap.
4. **Catch me when I'm on my phone:** a normal-level check-in that's due while the screen is off waits a
   few minutes (up to a limit) for the screen to come on. Fewer nudges buzz unseen in a pocket, so fewer
   are counted as "ignored", which matters because ignored check-ins feed the avoidance logic.
5. **Calendar, read-only** (optional, as you said): meetings from your phone's calendar show up as
   meetings automatically, with their reminders and digests, without telling the assistant about them.
   This uses Android's local calendar store, so it works offline and needs no Google API setup.
   Writing to the calendar can come later, if ever.
6. **Quiet focus:** optionally turn on Do Not Disturb during a focus session (except the app's own
   alarms and hard stops), and back off when it ends.
7. **Distinct vibrations** per level, so you know "urgent" vs "normal" without looking.
8. **Places:** "remind me when I get home / when I'm at the supermarket" (geofences). Needs background
   location permission, so it's later and opt-in.
9. **Driving:** hold check-ins while you're driving (Android's activity recognition).
10. **Scroll check** (opt-in, and only if you want it): after 20+ minutes in a feed app, a gentle "want 5
    minutes on {next action} instead?". Powerful for ADHD, but intrusive; it would be off by default.

## 5. What changes on the Supabase side
- Migration `010_sync.sql`: `updated_at` on every table (with a trigger), plus an index. Additive: the
  current server keeps working against it.
- One Edge Function (TypeScript, Supabase's Deno runtime) with `/sync` and `/llm`, using the service key
  server-side. `OPENROUTER_API_KEY`, `OPENROUTER_MODELS`, `OWNER_USER_ID` move there as function secrets.
- IDs stay as they are (`bigserial`). After the cutover only the phone creates rows, so it hands out the
  next number itself. (If a second device that writes ever comes along, we'd move to UUIDs then.)
- A nice side effect: daily sync keeps the free Supabase project active (free projects pause after a
  week without activity).

## 6. Distribution: sideload, not the Play Store

For a personal app, install the APK directly ("sideloading": you allow your browser or Obtainium to
install apps once). Reasons:
- The Play Store limits exact alarms and full-screen alarms to apps whose main job is an alarm clock or
  calendar, and reviews each app. Sideloaded, you just grant the permissions.
- No $25 developer account, no review wait, updates whenever you like.

If you ever want to share the app with others, the Play Store (or its internal testing track) is a
later, separate step.

## 7. Risks and how we handle them

| Risk | Handling |
|---|---|
| **The port is big:** ~5,000 lines of Python plus ~1,750 of UI. Bugs creep in. | Tests ported first, logic before UI, shadow mode against the live server before the cutover. |
| Phone makers' battery savers kill alarms | Exact alarms, the 15-minute safety net, the battery-optimization exemption in the setup checklist, a "last tick" time in settings so you can see it's alive. |
| Phone lost or reset between syncs | The "when convenient" sync on top of the daily one; Android's own backup of the app's data as a second layer. |
| LLM slow or unavailable at alarm time | Short timeout and the template fallback, as today. |
| Time zones, DST, Shabbat edge cases | `java.time` handles zones properly; the Python tests for these are ported one for one. |
| You're new to Android | Small phases, each installable. Each PR explains what's new and why, like the phase docs do now. |

## 8. Open questions
1. **Which phone and Android version?** It decides the minimum version we support, and whether Live
   Updates (Android 16's ongoing progress notifications, nice for the focus timer) are worth doing.
2. **After the cutover, what happens to the web app?** Retire it, or keep a read-only page that shows the
   Supabase backup (useful on a computer, but up to a day old).
3. **Do you have a computer for Android Studio?** Not required (GitHub Actions builds the APK), but the
   emulator and live log make debugging much easier.
4. **Pushover:** drop it after the cutover, or keep it as a second channel for alarms during the first
   weeks, until the native alarm screen has earned your trust?

## Glossary
- **APK**: the installable app file. **Signing key**: proves an update comes from the same developer.
- **Gradle**: the build tool (like `pip` + a build script). Builds the APK from the Kotlin source.
- **Activity**: a screen the system can open (the main app, the full-screen alarm). **Compose**: the
  way the UI is written, as Kotlin functions (similar to React components).
- **Room**: the local SQLite database layer. **DAO**: the class that holds a table's queries.
- **WorkManager**: background jobs that must eventually run (sync, the safety net).
- **AlarmManager / exact alarm**: wakes the app at an exact time, even in deep sleep.
- **Doze**: Android's deep sleep for idle phones, which delays most background work.
- **Notification channel**: a category of notifications with its own sound and Do Not Disturb setting.
- **Foreground service**: background work the user can see (an ongoing notification), which Android
  doesn't kill. Used for the focus timer.
- **Broadcast receiver**: code that runs when the system announces something (phone restarted, time
  zone changed).
- **Edge Function**: a small server-side function hosted by Supabase, run on demand.
- **Emulator**: a virtual Android phone on a computer. **Logcat**: the phone's live log.
- **Sideloading**: installing an APK from outside the Play Store.
