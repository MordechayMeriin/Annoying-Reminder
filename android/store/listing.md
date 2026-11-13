# Play Console: texts and form answers

Copy these into the Play Console. They describe the finished app (through Phase G), not just Phase A, so
the forms don't need redoing every phase. If a later phase changes what data leaves the phone, this file
and [the privacy policy](../../docs/privacy-policy.md) change with it.

## Store listing (Grow → Store presence → Main store listing)

Only needed if the Console asks for it before an internal testing rollout.

- **App name:** Annoying Reminder
- **Short description** (80 characters max):
  `A personal ADHD assistant: one tap to start, gentle check-ins, alarms that ring.`
- **Full description:**

  > Annoying Reminder is a personal assistant for getting things started, kept going and come back to.
  > Say a task once, in plain Hebrew or English, and it works out the first step, proposes Today's 3 every
  > morning, and checks in with one-tap buttons: Start 5 minutes, Done, Later.
  >
  > Reminders are decided on the phone and arrive on time even offline, at three levels: a normal
  > notification, an urgent one that can get through Do Not Disturb, and a full-screen alarm that rings
  > until you answer. The more you avoid a task, the quieter it gets, not the louder.
  >
  > This is a private app for one person's own account.

- **App icon:** [icon-512.png](icon-512.png) (512 × 512)
- **Feature graphic:** [feature-graphic.png](feature-graphic.png) (1024 × 500)
- **Phone screenshots:** from the latest CI run's *screenshots* artifact. Play wants the long side at most
  twice the short side, so crop each to 1233 × 2466.
- **Category:** Productivity. **Contact email:** required by Play; yours.

## App content (Policy → App content)

| Form | Answer |
|---|---|
| **Privacy policy** | `https://github.com/MordechayMeriin/Annoying-Reminder/blob/main/docs/privacy-policy.md` |
| **App access** | *All or some functionality is restricted.* Instructions: "Personal app for its owner's private account, distributed on the internal testing track only. Login is by an email code sent to the owner; there is no sign-up." |
| **Ads** | No ads. |
| **Content rating** | Category: *All other app types*. Answer **No** to every question (no violence, sexuality, language, drugs, gambling; users can't interact or share content with each other; no location sharing; no purchases). Expected rating: Everyone / PEGI 3. |
| **Target audience** | 18 and over. Not designed for children. |
| **News app** | No. |
| **Government app** | No. |
| **Financial features** | None. |
| **Health apps** | None. (It's a productivity tool, not a medical one.) |
| **Data safety** | See below. |

### Full-screen intent (App content → Full-screen intent, or the prompt on upload)

Use: **Alarm clock** functionality. Text if asked:

> The app's loudest reminder level is an alarm the user set up (a meeting, a hard stop, a deadline). Like an
> alarm clock, it shows a full-screen alarm over the lock screen that rings until the user dismisses it.

Exact alarms use `SCHEDULE_EXACT_ALARM` ("Alarms & reminders"), which needs no declaration. The app has no
foreground service, no background location, and doesn't read usage stats.

### Data safety

**Does your app collect or share any of the required user data types?** Yes.
**Is all of the user data encrypted in transit?** Yes (HTTPS only).
**Do you provide a way for users to request that their data is deleted?** Yes: the owner controls the
Supabase project and can delete everything there; uninstalling deletes everything on the phone.

| Data type | Collected | Shared | Optional? | Why |
|---|---|---|---|---|
| Personal info → **Email address** | Yes | No | Required | Account management (login) |
| App activity → **Other user-generated content** (tasks, notes, chat messages) | Yes | No | Required | App functionality (backup to the owner's database; messages are written by an AI model) |
| App info and performance → **Crash logs**, **Diagnostics** | No* | No | | |

\* The Diagnostics log stays on the phone unless you tap Share yourself. Crash reports in the Play Console
come from Google Play, not from the app.

"Shared" is **No** because Supabase (database), AWS (the server function) and the AI provider only process
data on the owner's behalf, which Play's form counts as service providers, not sharing. None of it is used
for ads or analytics, and none is sold.
