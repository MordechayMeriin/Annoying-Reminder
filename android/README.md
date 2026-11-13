# The Android app

The native Android version of Annoying Reminder: Kotlin + Jetpack Compose, Android 10 and up. The full
plan is in [docs/android-plan.md](../docs/android-plan.md); what each phase added is in `docs/android-phase-*.md`.

**Phase A ("hello, Android")** is in: logging in with your Supabase email code, the three notification
channels, the first-run checklist, the Diagnostics screen, and test buttons for a notification and an
alarm. There are no tasks yet: those come with Phase B.

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

### Project layout

```
android/app/src/main/java/com/annoyingreminder/app/
  ReminderApp.kt        starts first: the log, the channels, the login
  MainActivity.kt       the app's screen
  auth/                 Supabase email-code login (plain HTTPS), keeping you logged in
  notify/               the three channels (normal / urgent / alarm), showing notifications
  alarm/                the test alarm: exact alarm → receiver → full-screen alarm; set again after a restart
  setup/                the first-run checklist: what to allow, and the settings screen for each
  diag/                 the Diagnostics log
  ui/                   the screens (Compose): login, home, checklist, diagnostics, alarm
android/app/src/test/   tests, including the screenshot tests (ui/ScreenshotTest.kt)
android/store/          Play Store texts, icon and feature graphic
```

## One-time setup

These are yours to do, in a browser. Steps 1–3 can start today; Google's identity check can take a few days.

### 1. Repository variables (Supabase)

GitHub → the repo → Settings → Secrets and variables → Actions → **Variables** → New repository variable:

| Name | Value |
|---|---|
| `SUPABASE_URL` | The same as the web app's (Render → annoying-reminder-web → Environment) |
| `SUPABASE_ANON_KEY` | The same as the web app's |

They're public by design (the web app sends them to every browser), so they're variables, not secrets.
Without them the app builds but its login screen says it has no Supabase settings.

### 2. Google Play developer account

[play.google.com/console](https://play.google.com/console) → sign up as a **personal** account ($25 once),
and verify your identity. Then:

1. **Create app**: name *Annoying Reminder*, app (not game), free.
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
keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Annoying Reminder"
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
   *App permissions* add Annoying Reminder → tick **Release apps to testing tracks** → Invite.
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
| Variable | `SUPABASE_URL`, `SUPABASE_ANON_KEY` | Login (built into the app) |
| Variable (optional) | `PLAY_RELEASE_STATUS` | `completed` (default) or `draft` |
| Secret | `ANDROID_UPLOAD_KEYSTORE_BASE64`, `ANDROID_UPLOAD_KEYSTORE_PASSWORD`, `ANDROID_UPLOAD_KEY_ALIAS`, `ANDROID_UPLOAD_KEY_PASSWORD` | Signing the bundle |
| Secret | `PLAY_SERVICE_ACCOUNT_JSON` | Uploading to Play |

The release job runs in a GitHub environment called `play`; the secrets can live either on the repository
or on that environment.
