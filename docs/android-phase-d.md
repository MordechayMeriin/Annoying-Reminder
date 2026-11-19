# Android: Phase D (chat and the coach)

Implements Phase D of [the Android plan](android-plan.md): the assistant is back, running on the phone. You can talk to
it in the chat again, and it writes the check-ins, the morning plan, the mid-day check-ins, the evening review and the
re-entry message. The phone builds the prompts, runs the agent and its 12 tools against its own database, and calls
the backend only for the model itself. The OpenRouter key stays on the Lambda. When the model can't answer (no
internet, the daily cap, a timeout), the reminders still go out with Phase C's template wording. This file is written
to be pasted into the pull request description.

## What you do

**Before you start:** the assistant only works through the **Lambda** (Phase C, part 2). Render has no `/llm`. If the
app still points at Render (the `API_URL` variable isn't set), do Phase C's part 2 first.

1. **Merge this into `main`.** GitHub Actions deploys the backend (the *Backend* workflow) and uploads the app to Play
   (the *Android* workflow). Update the app on the phone.
2. **Give the Lambda the OpenRouter key** (about 3 minutes, in AWS CloudShell, in the same region as before):
   ```
   cd Annoying-Reminder && git pull && cd backend
   python3 scripts/aws_lambda_setup.py
   ```
   This time it asks only for what the function doesn't have yet: `OPENROUTER_API_KEY` (typed, not shown) and
   `OPENROUTER_MODELS`. Copy both from **Render → annoying-reminder-api → Environment**. It also gives the function
   60 seconds per request instead of 30, because a model call can take 25.
3. **Try it:** write anything in the chat ("dentist Thursday at 10"). You should see *✓ Sent*, the steps
   ("📅 Saving a new meeting: Dentist"), the typing dots, then the reply. If it says *The assistant isn't set up on the
   server yet*, step 2 didn't finish.

**Done when:** talking to the assistant on the phone feels like it did in the web app.

## What's new

### Android: the assistant (`llm/`), ported one for one
- **`Agent.kt`**: `agent.py`. It saves your message, finds the tasks it's probably about (your last check-in, today's
  messages, matching words), and picks the playbooks (stuck, avoiding, overwhelmed…). It builds the system prompt
  with the "likely topic" block and today's chat only. Then it runs the tool loop and links the turn to the tasks the
  model actually acted on. A reply about exactly one open task gets ✓ Done. A tap on Too big / Unclear / Boring is
  recorded by code, so it never depends on the model. *Try again* answers the same message without saving it twice.
- **`ToolLoop.kt`**: `llm_loop.py`. Tools run one at a time. Bad arguments go back to the model once, with the
  error. A tool call written as text (JSON or Python style, as Gemma does) is recovered once. After two errors it
  gives up.
- **`Tools.kt`, `ToolArgs.kt`**: `tools.py`, all 12 tools, now writing to Room: add, list, update, complete and
  cancel tasks; schedule a follow-up, add a step, log how you relate to a task, mark it blocked; set Today's 3; change
  a setting; remember a preference. Validation is as forgiving as the server's: numbers as text, empty optional
  values and unknown keys are fine. Times the model picks are clamped to 10 minutes … 7 days.
- **`Context.kt`**: `context.py`. The compact plain-text task blocks, histories and message lists the model reads.
- **`Coach.kt`**: `coach.py`. A check-in about one or more tasks, with its next action first. The morning plan picks
  Today's 3 with `set_focus`; if it doesn't, code's pick stands. Then the mid-day check-in, the evening review and the
  re-entry message. Meeting and hard-stop lines are always written by code, and a message with only hard stops never
  asks the model. Each message falls back to its Phase C template when the model doesn't answer.
- **`Prompts.kt`**: `prompting.py`. The prompt files are the backend's own (`backend/app/prompts/`), copied into the
  app when it's built. After a backup, at most twice a day, the app asks the backend for its set (`GET /prompts`) and
  uses it if it's newer. So a changed prompt only needs the Backend workflow, not a new app.
- **`Llm.kt`**: one model call through the backend's `POST /llm`, with your login.

### Android: the tick, now with the coach (`remind/Ticker.kt`)
A tick now runs in three steps, so a slow model never holds the database:
1. **plan** (one transaction): what goes now, postponements, parks, skipped daily jobs;
2. **write** (no transaction): the coach writes each message, within 30 seconds for the whole tick (an alarm can't
   keep the app awake for long). After that, templates;
3. **deliver** (one transaction): the messages, the notifications, the next check-in times, and the daily jobs marked
   as done.

Nothing is marked as sent before step 3. So if Android stops the app half-way, the next tick sends it. A task you
finish while its check-in is being written isn't contacted about. The tick log says which messages the model wrote
(`…, written by google/gemini-2.5-flash`).

### Android: the chat screen
- **Reply buttons** under the latest message: ✓ Done, Later, Not now, Move overdue to next week, Drop them / Keep and
  What now? act on your data directly, with no model involved. Buttons like *I'm stuck* send their words as your reply.
- **Sending… / ✓ Sent** under your message, then the assistant's steps ("✏️ Updating task: Taxes") and the typing
  dots. If no reply comes, it says why, with **Try again**. A turn keeps going if you leave the chat. Turns run one at
  a time.
- **🎤 Dictation**: Android's own speech recognizer, in Hebrew or English (the same rule as the buttons' language).
- **Share to the app**: share text from WhatsApp, an email or a link to *ProMinder*. The assistant saves the to-do in it.
- **New task**: long-press the app icon. The chat opens, ready to type or dictate.
- **Reply on a notification**: every message notification (not the alarm) has *Reply*. Your text goes to the
  assistant as a chat message. Its answer comes as a notification, or in the chat if the app is open.
- **The model's name** above the latest reply, when ⚙ *Show which AI model replied* is on.
- New screenshots: *4d_chat_working* and *4e_chat_chips*.

### Backend: `POST /llm` and `GET /prompts` on the Lambda (`backend/app/llm_proxy.py`)
- **`/llm`**: one model call for the phone. It uses the same `llm.py` as before: the key, the fallback models, the
  reasoning setting, the timeout. Owner login required. Limits:
  - a **daily cap** (`LLM_CALLS_PER_DAY`, default 300), now counted in DynamoDB so every Lambda instance sees the
    same count (the 301st call is a 429 with `"code": "limit"`);
  - a **rate limit** (`LLM_PER_MINUTE`, default 30);
  - a **timeout** (`LLM_TIMEOUT_S`, default 25; the phone can ask for less, never more).

  If the model can't answer, the server returns 503 and the phone uses its templates. If the key isn't set yet, the
  503 says to run the setup script.
- **`/prompts`**: every prompt file of this deployment, with a version.
- **`scripts/aws_lambda_setup.py`**: now also sets `OPENROUTER_API_KEY` and `OPENROUTER_MODELS`, and the 60-second
  timeout. Run again, it asks only for values the function doesn't have yet.
- **Tests** (`tests/test_llm_proxy.py`, 10): login required, the answer and model passed through, tool calls passed
  through, the timeout ceiling, the shared daily cap, the rate limit, 503s, not set up yet, bad requests, the prompt
  set. All 421 backend tests pass.

### Tests
Android: **285** (230 before), plus lint and the screenshots.
- `ToolArgsTest` (8): `test_tools.py`.
- `AgentTest` (18): `test_agent.py`, `test_chat_progress.py`, the model part of `test_model_tag.py`, plus Try again,
  reason buttons, settings and preferences from the chat, and `set_focus`.
- `CoachTest` (9): the LLM paths of the tick. That covers its words and model vs the template, meeting lines from
  code, no model call for hard stops, the coach's follow-up clamped, an avoided task keeping code's 3 days, the
  morning plan's `set_focus`, the fallback pick, the evening review, and a task finished mid-write.
- `ChatTest` (2), `BackendLlmTest` (6), `PromptsTest` (3), `StoreAssistantTest` (8).
- `RemindersTest`: the Reply button and the reply's round trip from a notification.

## Decisions and deviations from the plan

1. **The tick was split in three** (see above). Phase C ran it in one transaction. A model call inside it would hold
   the database for seconds and lose everything if Android stopped the app.
2. **30 seconds of model time per tick, 25 per message.** An alarm's receiver can't keep the app awake for long. So a
   tick that would take longer sends the rest with templates. In Doze the phone may also have no internet at alarm
   time: then too, the template goes out on time.
3. **▶ Start, ▶ Another round and Stop buttons are hidden** until focus sessions come in Phase E. They're still stored
   on the messages (and backed up), so they reappear then. What now?, Low energy and Something else already work:
   they're pure code.
4. **A chat turn may take 90 seconds** (60 on Render): a cold Lambda adds a few seconds to each model call.
5. **A reply from a notification runs as a background job** (WorkManager), not in the notification's receiver, which
   Android stops after a few seconds.
6. **The daily cap is in DynamoDB.** Render counted calls in memory, which was enough with a single server. Lambda can
   run several instances at once.
7. **The New task shortcut is added by the app at start** (a dynamic shortcut), not declared in a file. That way it
   also works in the debug build, whose package name differs.
8. **The prompts ship with the app**, copied from `backend/app/prompts/` at build time, so there's one copy to edit.
   The Android workflow now also runs when a prompt changes.
9. **No database migration**: the new queries work on version 1's tables.
10. **A test fix outside the phase.** Three notification tests in `RemindersTest` could fail at real daily-job times:
    the app's own 15-minute safety-net tick runs on the real clock during tests. They now look for their own
    notifications.
