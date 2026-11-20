# Android: Phase E (focus sessions and alarms)

Implements Phase E of [the Android plan](android-plan.md): focus sessions and the full-screen alarm come to the phone.
With this, everything the web app did works on the phone. ▶ Start opens a timer on the task's next action. A silent
countdown sits in the notification shade, and the session's end arrives as an urgent check-in with ✓ Done ·
▶ Another round · Stop. After you stop, or don't answer, the app asks "Where did you stop?" (optional). An alarm-level
message now rings again every 2 minutes until you answer it, as Pushover's alarms did. This file is written to be
pasted into the pull request description.

## What you do

1. **Merge this into `main`.** The *Android* workflow uploads the app to Play. Update the app on the phone. The backend
   doesn't change.
2. **Try a session:** tap ▶ next to one of Today's 3. The timer opens. Leave it with ✕: a bar above the chat and a
   countdown in the notification shade keep counting. At the end you get an urgent message with ✓ Done and
   ▶ Another round.
3. **Optional:** the countdown has its own notification channel, *Focus timer* (silent). If you'd rather not see it on
   the lock screen, switch it off in the app's notification settings. The session still runs.

**Done when:** everything in the README works on the phone.

## What's new

### Focus sessions (`data/Sessions.kt`, ported from `actions.py` and `sessions.py`)
- **▶ Start** from Today's 3 or a task's page (25 minutes), from a reply button (5 minutes, or What now?'s pick), or
  from a check-in's notification (5 minutes; the app opens straight on the timer).
- As on the server: at most one session is open, and starting one closes the other. The task's own check-in inside
  the session is dropped, because the end check-in replaces it. Starting counts as a fresh start (no longer avoided or
  parked). The chat gets `[▶ 25 min: Open the form (Taxes)]`, which counts as you answering.
- **✓ Done, Stop, ▶ Another round** (as long as the last round), **5 / 15 / 25 / 45 and +5**. A new length after the
  end check-in brings the check-in back at the new end. Finishing, cancelling or parking the task ends its session.
  Every outcome is recorded with its actual minutes (`stopped, 12 of 25 min`), as on the server. The phone used to
  skip that event.
- **"Where did you stop?"**: after Stop, or 15 minutes of silence, a small field above the message box (with 🎤). Save
  keeps the line as the task's resume note: it shows on the next timer and goes into the next check-in. Skip, or doing
  nothing for 3 hours, changes nothing. Saying it in the chat closes the field too (Phase D).
- **The day's wins** on Today's 3: `1/3 done · ⏱ 2 sessions`.

### The timer (`ui/SessionScreen.kt`)
- Full screen, like the web app's: a ring that empties as time runs, the next action, "You stopped at: …", the
  lengths (ones that have already passed are greyed out), and ✓ Done · +5 · Stop. When time is up, the ring closes in
  green and the buttons become ✓ Done · ▶ Another round · Stop.
- **✕ hides it.** The session keeps running: a bar above the chat (`⏱ 17:30 · Write the report`) opens it again.
- New screenshots: *12_session*, *12b_session_over*, *4f_home_session*.

### The session's end (`remind/Ticker.kt`)
- The tick sends the end check-in **at the end itself**, because the next exact alarm is already the session's end.
  (The server needed up to 5 minutes.) It's a template, so it costs nothing and never fails: `⏱ Time's up: Open the
  form. Done, another round, or stop?` It's urgent, with ✓ Done and ▶ Another round on the notification and Stop in
  the chat.
- Quiet hours, Shabbat and the daily cap still hold. The gap and the per-task cap don't, because you asked for it.
  An end that can't be sent waits, and the log says why (`Held the end of focus session #4 …: quiet hours`).
- **Silence is an answer:** 15 minutes after the end check-in, the session is recorded as paused, and nothing more is
  sent. While a session runs, other check-ins wait until it ends + 10 minutes. Meetings and hard stops still come
  through. All of this was ported in Phase C and now applies to sessions started on the phone.

### The countdown notification (`remind/SessionTimer.kt`, `notify/Notifier.kt`)
- A silent, ongoing notification on a new channel, **Focus timer**. It counts down to the end (Android's own
  chronometer, so it costs nothing while it runs) and has ✓ Done and Stop. A tap opens the timer. It goes away at the
  end, when the urgent end check-in takes over, or when the session closes. It's refreshed after every change and
  every tick.

### The alarm and its repeat rules (`alarm/AlarmRings.kt`, `alarm/AlarmActivity.kt`)
- An alarm-level message rings full screen on the alarm volume for **up to a minute**, then **again every 2 minutes**
  (new setting: ⚙ *How loud → Alarm repeats every*, 1–30 minutes, the web app's key) until you answer it. It stops
  for good at the end of its window: an hour at most, and never into quiet hours or Shabbat. If there isn't time for
  even one repeat, the message is urgent instead, as before.
- **The alarm screen** has ✓ Done, ⏰ Later, Open (unlocks and opens the chat) and Dismiss. Its notification has
  ✓ Done, Later and Dismiss.
- **Anything you write or tap in the app stops it**, as the server's `cancel_alarms` did: a chat message, a reply
  button, ✓ / Later / cancel on any task, a session. The tick's own messages don't count.
- Each repeat is an *alarm clock* alarm (`setAlarmClock`). Doze lets those through every time; ordinary exact alarms
  get through at most once every 9 minutes. A ringing alarm survives a restart.
- The Diagnostics log shows every ring and why it stopped (`Alarm for message #88 stopped: ✓ Done on the alarm screen`).

### Notification buttons
- A check-in about one task: **▶ Start 5 min · ✓ Done · Reply**, as on the web app. In Phase C it was ✓ Done · Later;
  Later is still in the chat's reply buttons. ▶ Start opens the timer, because Android only lets a notification open
  a screen directly (an activity), not from the background.
- A session's end: **✓ Done · ▶ Another round · Reply**.

### Tests
Android: **309** (285 before), plus lint and the screenshots.
- `SessionsTest` (12): `test_phase2_flow.py`'s session tests. They cover start and its chat event, the end check-in
  and then silence → paused, the focus hold with a meeting coming through, the task's own check-in replaced, Done at
  the end, another round (also after silence), Stop → "Where did you stop?" → used by the next check-in, ignoring it,
  finishing the task another way, +5 after the end check-in, an end in quiet hours, and the next wake at the
  session's end.
- `RemindersTest` (11, 6 before): the new buttons (▶ Start opens the app), the alarm's one-minute ring and its repeat
  2 minutes later as an alarm-clock alarm, the last ring cut short at the end of the window, a tap or Dismiss
  stopping it (the tick's messages don't), the countdown with ✓ Done and Stop, and a session end's buttons.
- Screenshots: the timer, time's up, the home screen with a session running, and the alarm with ✓ Done · ⏰ Later ·
  Open.

## Decisions and deviations from the plan

1. **Each alarm ring lasts up to a minute, then it's quiet until the next one.** Pushover repeated a short sound every
   2 minutes. Ringing nonstop for an hour would be far louder than the web app was, so this matches its rhythm.
   Between rings the notification goes away, but the alarm screen stays open if it was showing.
2. **The session's end is on time**, not "within 5 minutes": the phone wakes exactly then.
3. **The countdown is a notification, not a sound in the app.** On the web the ring beeped when the page was open.
   On the phone the urgent end check-in sounds whether the app is open or not, so there's no second sound.
4. **Later left the check-in notification** to make room for ▶ Start 5 min (Android shows three buttons, and Reply is
   one), as on the web app. It's still in the chat and on the alarm.
5. **▶ Start on a task's page** (25 minutes) is new. The web app only had it on Today's 3 and the reply buttons.
6. **No database migration**: the new queries work on version 1's tables. Sessions were already backed up.
7. **A fix to earlier phases:** closing a session (done, cancelled, parked, or silent) now writes the same event as
   the server, with its minutes. A silent session's event is `paused`, not `session_ended`.
