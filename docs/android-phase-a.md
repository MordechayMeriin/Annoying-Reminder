# Android: Phase A ("hello, Android")

Implements Phase A of [the Android plan](android-plan.md): a Kotlin + Compose app in `android/` that logs
in, notifies, rings, and tells you what it's doing. No tasks yet, and the web app and server are untouched:
nothing changes for the old app until the handover at the start of Phase B. This file is written to be
pasted into the pull request description.

## What you do

Everything is in [android/README.md](../android/README.md) → *One-time setup*: two repository variables
(Supabase), the Play developer account and its forms ([answers](../android/store/listing.md)), an upload key
made in Google Cloud Shell, the first upload by hand, then a service account for automatic uploads.

**Done when:** the app comes from the Play Store, and the 1-hour test alarm rings on time after the phone
sat untouched with the screen off (the Doze test). The result shows on the home screen and in Diagnostics.

## What's new

- **`android/`**: a Gradle project (Kotlin 2.2, Compose, Material 3), `minSdk 29` (Android 10, the S9),
  `targetSdk 36` (Play's current requirement). Application id `com.annoyingreminder.app`, permanent once
  uploaded. Debug builds get `.debug` appended, so they can sit next to the Play version.
- **Login** (`auth/`): Supabase's email code over plain HTTPS (OkHttp), the same flow and account as the web
  app, with `create_user: false` so nobody else can sign up. The session is kept in private storage
  (backups off), refreshed before it expires, and survives being offline: only Supabase saying "this login
  is gone" logs you out, never a network error (same rule as the server's 503-not-401).
- **Notification channels** (`notify/Channels.kt`): *Normal*, *Urgent* (the ringtone, high priority, asks
  to bypass Do Not Disturb) and *Alarm* (the alarm sound at alarm volume, full-screen, insistent). The ids
  are permanent: Android doesn't let an app change a channel's sound after creating it.
- **First-run checklist** (`setup/`): opens by itself after the first login; only shows what applies to the
  phone. On the S9: notifications, battery unrestricted, Samsung's never-sleeping list (ticked by hand,
  Android can't report it) and Do Not Disturb for Urgent and Alarm. On Android 12+ also "Alarms &
  reminders", on 14+ full-screen alarms. Each button opens the exact settings screen, with the app's own
  settings page as the fallback; rows re-check when you come back.
- **Test buttons**: *Notify me now*, *Urgent now*, *Alarm in 1 min*, *Alarm in 1 hour* (the Doze test).
  The alarm is a `setAlarmClock` exact alarm (inexact fallback when "Alarms & reminders" is off). When it
  fires, a receiver posts an insistent full-screen notification that opens the alarm screen over the lock
  screen; it rings until Dismiss (or 10 minutes). Lateness is measured and saved.
- **Boot receiver**: a waiting test alarm is set again after a restart or an app update; if its time passed
  while the phone was off, it rings right away. Phase C's scheduler reuses this.
- **Diagnostics** (`diag/`): the app's own log (app starts, logins, alarms set/rung/dismissed with
  lateness, settings opened, crashes), capped at ~250 KB, plus a summary (version, phone, every permission,
  the last test alarm) and Share.
- **CI** (`.github/workflows/android.yml`): tests, lint, screenshots and a debug APK on every push to
  `android/`; on `main` the signed bundle goes to the internal testing track. The bundle is also kept as an
  artifact, for the first manual upload.
- **Store**: [privacy policy](privacy-policy.md), [form answers and listing texts](../android/store/listing.md),
  icon and feature graphic.
- **Tests** (47): the auth client against a fake Supabase (sends, verifies, refreshes, every error turned into
  a sentence), the login rules (offline keeps you in, a dead refresh token logs you out), the checklist per
  Android version and maker, alarm lateness wording, the log's trimming, and the whole alarm path on a
  simulated Android 10 and 14 (set → fire → full-screen insistent notification → dismiss; restart; inexact
  fallback). Plus 7 screenshot tests.

## Decisions and deviations

1. **Four test buttons, not two:** *Urgent now* tests the Do Not Disturb path, and *Alarm in 1 hour* is
   the Doze test itself (a 1-minute alarm doesn't give the phone time to doze).
2. **No Room yet.** The log is a small text file; Room arrives with the real data in Phase B.
3. **Toolchain one release behind the newest** (AGP 8.13, Kotlin 2.2, Compose BOM 2025.12): versions whose
   behavior I know well. Play only cares about `targetSdk`, which is current. Lint lists the newer versions;
   upgrading is a separate, boring PR.
4. **Upload key made in Cloud Shell**, not by CI: the repository is public, and workflow artifacts of a public
   repository can be downloaded by anyone signed in to GitHub.
5. **Privacy policy as a page in the repo** (a public GitHub URL), so no extra hosting.
6. **The emulator run on newer Android versions** waits for a phase with something version-specific to
   check; Phase A covers Android 14+ behavior with Robolectric (the inexact-alarm fallback, the checklist).
