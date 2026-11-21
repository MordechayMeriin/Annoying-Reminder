# The Android app

ProMinder, the native Android version of Annoying Reminder: Kotlin + Jetpack Compose, Android 10 and up. The full
plan is in [docs/android-plan.md](../docs/android-plan.md); what each phase added is in `docs/android-phase-*.md`.

**Phase A ("hello, Android")** is in: signing in with Google (only your account gets in), the three notification
channels, the first-run checklist, the Diagnostics screen, and test buttons for a notification and an
alarm.

**Phase B (the handover)** is in: the phone keeps your tasks, Today's 3, the chat history and your settings in its
own database, works offline, and backs them up to the server a few minutes after each change. The first time you
open it, *Move your data to this phone* does the handover (the web app switches off); after a reinstall the same
screen restores the backup. Details: [docs/android-phase-b.md](../docs/android-phase-b.md).

**Phase C (reminders)** is in: the phone decides on its own when to remind you, with the server's rules (check-ins,
meetings, hard stops, the morning plan, mid-day check-ins, the evening review, the week's and tomorrow's meetings,
quiet hours, Shabbat and holidays, normal / urgent / alarm), template wording, ✓ Done and Later on the
notification, and a tick log in Diagnostics. The backend moves from Render to AWS Lambda, with a daily email if the
phone goes silent. Details: [docs/android-phase-c.md](../docs/android-phase-c.md).

**Phase D (chat and the coach)** is in: the assistant runs on the phone. The chat is live again (reply buttons,
*Sending… / ✓ Sent*, the typing dots and the assistant's steps, dictation, share-to-app, the *New task* shortcut on
the app icon, Reply on a notification), and the check-ins and daily messages are written by the LLM, with the
templates as the fallback. The OpenRouter key stays on the Lambda. Details: [docs/android-phase-d.md](../docs/android-phase-d.md).

**Phase E (focus sessions and alarms)** is in: ▶ Start opens a timer (a ring, the next action, 5 / 15 / 25 / 45, +5),
with a silent countdown in the notification shade and an urgent check-in at its end (✓ Done · ▶ Another round · Stop);
"Where did you stop?" after a stopped or unanswered session; and the alarm rings full screen again every 2 minutes
until you answer it. Details: [docs/android-phase-e.md](../docs/android-phase-e.md).

**Phase F (clean up)** is in: the backup runs once a day, plus a cheap run on Wi-Fi when you leave the app with
changes, instead of a few minutes after every change. The web app, Render, cron-job.org and Pushover are gone, and
`backend/` holds only the Lambda's code. Details: [docs/android-phase-f.md](../docs/android-phase-f.md).

## How it's built

You don't need Android Studio or anything on your laptop. GitHub Actions
([`.github/workflows/android.yml`](../.github/workflows/android.yml)) does it all on every push that
touches `android/`:

| Step | What it does |
|---|---|
| Unit tests | The logic, the login, and the alarm path on a simulated Android 10 (the S9) and Android 14 (Robolectric). |
| Lint | Android's own checks (wrong API levels, manifest mistakes). |
| Screenshots | Renders every screen to a PNG at the S9's screen size (Roborazzi). They're in the run's **screenshots** artifact. |
| Debug APK | An installable test build in the **app-debug** artifact. |
| Release (only on `main`, or "Run workflow" with *upload* ticked) | Builds the signed app bundle and uploads it to Google Play's internal testing track. |

Locally (optional, needs JDK 17 and the Android SDK): `cd android && ./gradlew :app:testDebugUnitTest`,
`./gradlew :app:recordRoborazziDebug` (screenshots into `app/screenshots/`), `./gradlew :app:assembleDebug`.

### Running it from Android Studio

Open the `android/` folder (not the repo root). For login to work, add the Google client id to
`android/local.properties`, which Android Studio creates and git ignores:

```properties
GOOGLE_WEB_CLIENT_ID=1234567890-abc.apps.googleusercontent.com
```

Google only signs in builds it knows: a build from Android Studio is signed with your computer's debug key
and installs as `com.prominder.myapp.debug`, so it needs its own Android client (step 1.3 below, with that
package name and the SHA-1 from `./gradlew signingReport`).

Then sync Gradle, pick the S9 (USB debugging on) or an emulator, and press Run. The debug build installs as
`com.prominder.myapp.debug`, next to the Play Store version.

**Behind an HTTPS filter (NetFree, Rimon, etc.).** The filter reads secure traffic by putting its own
certificate in the middle. The app trusts certificates installed on the device
(`res/xml/network_security_config.xml`), but the certificate must be installed there: on the phone it
usually already is (the filter's setup does it), while an emulator starts without it. Install it in the
emulator under Settings → Security → Encryption & credentials → Install a certificate → CA certificate.
If it's missing, login says the certificate check failed.

### Project layout

```
android/app/src/main/java/com/prominder/myapp/
  ReminderApp.kt        starts first: the log, the channels, the login
  MainActivity.kt       the app's screen
  auth/                 Google sign-in (Credential Manager), the backend's login token, keeping you logged in
  data/                 your data: the Room database, the task rules (Store), dates, settings
  sync/                 the backup: restore (the handover), upload, the WorkManager job
  notify/               the three channels (normal / urgent / alarm), showing notifications
  alarm/                the test alarm: exact alarm → receiver → full-screen alarm; set again after a restart
  setup/                the first-run checklist: what to allow, and the settings screen for each
  diag/                 the Diagnostics log
  i18n/                 the app's language: the phone's, or English / עברית picked in ⚙ Settings
  ui/                   the screens (Compose): login, handover, home, tasks, task, form, settings, checklist,
                        diagnostics, alarm
android/app/src/main/res/values/strings.xml      the app's words in English (values-iw/: in Hebrew)
android/app/schemas/    each database version's schema (Room), for future migrations
android/app/src/test/   tests, including the screenshot tests (ui/ScreenshotTest.kt)
android/store/          Play Store texts, icon and feature graphic
```

### Languages

The app's own words (screens, buttons, notifications, the checklist, settings) are string resources: English in
`res/values/strings.xml`, Hebrew in `res/values-iw/strings.xml` (Android's folder name for Hebrew). In Hebrew the layout
is right-to-left. The language is the phone's unless you pick one in ⚙ Settings → Language (on Android 13+ also in
the phone's Settings → Apps → ProMinder → Language); `i18n/AppLanguage.kt` applies it through AppCompat's per-app
language, back to Android 10. What the assistant writes, and the reply buttons under its messages, follow the language
you write in, as before. The Diagnostics screen stays English, like its log. The app's name is "ProMinder" in every
language.

To add a language: a `res/values-xx/strings.xml` with every string (lint fails on a missing one), and its tag in
`AppLanguage.SUPPORTED`. The build lists it for Android's language setting by itself (`generateLocaleConfig`).

## One-time setup

These are yours to do, in a browser. Steps 1–3 can start today; Google's identity check can take a few days.

### 1. Google sign-in

How it works: Google shows its account sheet on the phone and gives the app a token signed by Google. The
app sends it to the backend (`POST /auth/google`), which checks Google's signature and that the account is
`OWNER_GOOGLE_EMAIL`, and gives the app its own login for a year. Anyone else's account is refused.

In the [Google Cloud console](https://console.cloud.google.com), in any project (the one for Play uploads in
step 5 is fine):

1. **Google Auth Platform** (APIs & Services → OAuth consent screen) → **Get started**: app name `ProMinder`,
   your email as support and contact email, audience **External** → Create. Then **Audience → Test users →
   Add users**: your Gmail address. (Leaving the app in "Testing" is fine: only test users can sign in, and
   that's only you anyway.)
2. **Clients → Create client → Web application**, name `ProMinder server` → Create. Copy the **Client ID**
   (it ends in `.apps.googleusercontent.com`). This is the one the next steps call "the web client id",
   although there's no web app involved: it's the id the backend checks tokens against.
3. **Clients → Create client → Android**, name `ProMinder (Play)`, package name `com.prominder.myapp`, and the
   **SHA-1** from the Play Console: ProMinder → Test and release → **App integrity** → App signing →
   *App signing key certificate* → SHA-1. (Google Play re-signs the app with that key, so that's the
   certificate your phone sees.) Create; there's nothing to copy.
4. **The backend**: `GOOGLE_CLIENT_ID` = the web client id, `OWNER_GOOGLE_EMAIL` = your Gmail address.
   `backend/scripts/aws_lambda_setup.py` asks for both and puts them on the Lambda function.
5. **GitHub → the repo → Settings → Secrets and variables → Actions → Variables**: `GOOGLE_WEB_CLIENT_ID` =
   the same web client id. It's public by design (anyone could read it out of the app), so it's a variable,
   not a secret. Without it the app builds but its login screen says it has no login settings.
6. Build a new version: Actions → **Android** → Run workflow on `main` with *upload* ticked.

If signing in says "this build isn't registered in Google Cloud", the Android client's package name or SHA-1
doesn't match (step 3). The variable `API_URL` is the backend's address (the Lambda's function URL, which the
setup script prints); a release build refuses to start without it.

### 2. Google Play developer account

[play.google.com/console](https://play.google.com/console) → sign up as a **personal** account ($25 once),
and verify your identity. Then:

1. **Create app**: name *ProMinder*, package name `com.prominder.myapp` (permanent), app (not game), free.
2. **Privacy policy** (App content → Privacy policy):
   `https://github.com/MordechayMeriin/Annoying-Reminder/blob/main/docs/privacy-policy.md`
   (it's live once this branch is merged into `main`).
3. Fill in the **App content** forms with the answers in [store/listing.md](store/listing.md): app access,
   ads, content rating, target audience, data safety, full-screen intent.
4. **Testing → Internal testing → Testers**: create a list with your own Google account (the one on the S9),
   and open the **opt-in link** on the phone once, to accept.

### 3. Upload key

Every app on Play is signed. With Play App Signing (the default), Google keeps the real key; we only need
an **upload key**, which Google can reset if it's ever lost.

Make it in a browser terminal: open [shell.cloud.google.com](https://shell.cloud.google.com) (Google Cloud
Shell, free; it has Java) and run, choosing your own password:

```bash
keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=ProMinder"
```

```bash
base64 -w0 upload.jks
```

Then GitHub → Settings → Secrets and variables → Actions → **Secrets** → New repository secret:

| Secret | Value |
|---|---|
| `ANDROID_UPLOAD_KEYSTORE_BASE64` | the long line `base64` printed |
| `ANDROID_UPLOAD_KEYSTORE_PASSWORD` | the password you chose |
| `ANDROID_UPLOAD_KEY_ALIAS` | `upload` |
| `ANDROID_UPLOAD_KEY_PASSWORD` | the same password (keytool uses one password for both by default) |

Afterwards, in Cloud Shell: `rm upload.jks`. The repository is public, so the key never goes into the repo
or a workflow artifact; only into secrets.

### 4. The first upload is by hand

Google's API can't upload an app's very first build, so:

1. GitHub → Actions → **Android** → Run workflow (branch `main`, tick *upload*). With no Play
   credentials yet it builds the signed bundle and keeps it as the **app-release-bundle** artifact.
2. Download it, unzip, and in the Play Console: Testing → Internal testing → Create new release → upload
   `app-release.aab` → Save → Review → **Start rollout to Internal testing**. (Play may first ask you to
   accept Play App Signing: accept.)
3. On the phone, open the opt-in link from step 2.4 → *Download it on Google Play* → install.

### 5. Automatic uploads from then on

1. [Google Cloud console](https://console.cloud.google.com) → a project (any) → enable the **Google Play
   Android Developer API** → IAM → Service accounts → create one (no roles) → Keys → Add key → JSON.
2. Play Console → **Users and permissions** → Invite new users → the service account's email → under
   *App permissions* add ProMinder → tick **Release apps to testing tracks** → Invite.
3. GitHub secret `PLAY_SERVICE_ACCOUNT_JSON`: the whole JSON file's contents. Then delete the file.

From then on every merge into `main` that touches `android/` lands on your phone through the Play Store
within minutes. If an upload fails with "Only releases with status draft may be created on draft app",
the first release (step 4) wasn't rolled out yet; or set the repository variable `PLAY_RELEASE_STATUS` to
`draft` and roll each release out by hand.

### 6. Quick try-outs without a release (optional)

Play Store app on the phone → Settings → About → tap *Play Store version* seven times → General →
**Internal app sharing** on. Then any `.aab` uploaded at
[play.google.com/console/internal-app-sharing](https://play.google.com/console/internal-app-sharing)
gives a link that installs it.

## Phase A: done when…

1. The app comes from the Play Store (internal testing).
2. **The Doze test:** log in, go through the checklist, tap **Alarm in 1 hour**, lock the phone and leave it
   alone, screen off, for the hour. It must ring on time. The home screen and Diagnostics show the result
   ("rang on time", or how late), and Share sends the log.

Also worth trying once: **Notify me now** and **Urgent now** (with Do Not Disturb on, for urgent), **Alarm
in 1 min** with the phone locked, and restarting the phone while a 1-hour alarm is waiting (it's set again).

## Secrets and variables, all in one place

| Kind | Name | Used for |
|---|---|---|
| Variable | `GOOGLE_WEB_CLIENT_ID` | Google sign-in (built into the app) |
| Variable | `API_URL` | The backend: the Lambda's function URL (`backend/scripts/aws_lambda_setup.py` prints it). Required for a release |
| Variable | `AWS_DEPLOY_ROLE_ARN`, `AWS_REGION` | Deploying the backend to Lambda (the Backend workflow) |
| Lambda setting | `OPENROUTER_API_KEY`, `OPENROUTER_MODELS` | The assistant (Phase D). Set by `backend/scripts/aws_lambda_setup.py`, on the function itself, never in GitHub or the app |
| Variable (optional) | `PLAY_RELEASE_STATUS` | `completed` (default) or `draft` |
| Secret | `ANDROID_UPLOAD_KEYSTORE_BASE64`, `ANDROID_UPLOAD_KEYSTORE_PASSWORD`, `ANDROID_UPLOAD_KEY_ALIAS`, `ANDROID_UPLOAD_KEY_PASSWORD` | Signing the bundle |
| Secret | `PLAY_SERVICE_ACCOUNT_JSON` | Uploading to Play |

The release job runs in a GitHub environment called `play`; the secrets can live either on the repository
or on that environment. 
