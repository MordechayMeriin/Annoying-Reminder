# Privacy policy: Annoying Reminder (Android)

*Last updated: 13 November 2026*

Annoying Reminder is a personal assistant app built and used by one person for their own account. There is
no sign-up: only the owner's existing account can log in. This page says what the app does with data.

## What the app keeps, and where

- **On your phone:** your tasks, notes, chat messages, settings and the app's own diagnostics log, in the
  app's private storage. Other apps can't read it.
- **Backup:** a copy of your tasks, chat and settings is sent over HTTPS to the owner's own Supabase
  database (through a small server function on Amazon Web Services), so a new or reset phone can restore
  them.
- **Login:** your email address, used by Supabase Auth to send you a login code.

## What is sent to an AI model

To write messages (check-ins, the morning plan, chat replies), the relevant text (your recent chat and the
tasks it concerns) is sent over HTTPS through that server function to an AI model provider (via
OpenRouter). The text is used to write the reply. Offline, the app writes a template message instead and
sends nothing.

## What the app doesn't do

- No ads, no analytics, no tracking, and no data sold or shared for marketing.
- No location, contacts, microphone recordings or usage statistics are collected. (Dictation uses
  Android's own speech recognizer; the app receives only the resulting text.)
- The diagnostics log never leaves the phone unless you tap **Share** on the Diagnostics screen yourself.

Google Play may collect crash reports and install statistics under
[Google's privacy policy](https://policies.google.com/privacy).

## Deleting your data

Uninstalling the app deletes everything on the phone. The backup lives in the owner's own Supabase
project and can be deleted there at any time.

## Contact

Questions: open an issue at
[github.com/MordechayMeriin/Annoying-Reminder/issues](https://github.com/MordechayMeriin/Annoying-Reminder/issues).
