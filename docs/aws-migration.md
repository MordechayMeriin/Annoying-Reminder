# Moving from Supabase to AWS (DynamoDB + Cognito)

The app now stores its data in **DynamoDB** and logs in with **Cognito** (a code sent by email, as before).
The backend stays on Render and the timer on cron-job.org. This page is the one-time switch-over for an
existing setup. A new setup only needs [README → Setup checklist](../README.md#setup-checklist).

## What it costs

Nothing for one user, within AWS's **always-free** tier. These limits don't expire after 12 months:

| Service | Free every month | ProMinder's use |
|---|---|---|
| DynamoDB | 25 GB storage, 25 read + 25 write capacity units | a few MB; the table's capacity adds up to exactly 25 + 25 |
| Cognito (Essentials) | 10,000 monthly active users | 1 |
| SES (login emails) | (fractions of a cent per email) | a few emails a month |

Caveats:
- The free tier is **per AWS account and region**. If you already use DynamoDB in the same region, the 25
  units are shared. `aws_setup.py` also creates a **$1/month budget** that emails you if AWS ever expects
  a charge.
- **No automatic backups.** Point-in-time recovery costs money, so it's off. The data is small; an
  occasional export from the DynamoDB console (Exports to S3) is enough if you want one.
- SES starts in its "sandbox": it can only send to verified addresses. That's fine: the only recipient is
  you, and `aws_setup.py` verifies your address.
- Gmail may file the first login code under Spam (it's sent "from" your own address through AWS). Mark it
  "not spam" once.

## Step 0 (optional): let Claude do the AWS part

Claude Code can create and check the AWS resources itself through the **AWS connector**. In the session
that wrote this code the connector needed a fresh sign-in, so:

1. Open **https://claude.ai/customize/connectors**.
2. Find **AWS** and choose **Connect** (or **Reconnect**). Sign in to the AWS account you want ProMinder
   in, with a user that can create DynamoDB tables, Cognito user pools, SES identities, IAM users and
   budgets (an admin is simplest).
3. Start a **new** Claude Code session on this repository: connectors are only picked up when a session
   starts. Choose the branch `ccr-8144efb8-5h4d2n` (or `main`, once this work is merged).
4. Paste this as the first message:

   > Continue the AWS migration in docs/aws-migration.md. Use the AWS connector to do what
   > backend/scripts/aws_setup.py does, with my email `<your email>` and region us-west-2: SES identity
   > (I'll click the verification link), the DynamoDB table from app/db.py's table_spec, the Cognito user
   > pool + app client + my user, the IAM user with the table-only policy, and the $1 budget. Don't create
   > an access key in chat; I'll make it in the IAM console. Then give me the values for Render, the web
   > app and GitHub.

5. Claude asks before each change. Click the SES verification email when it arrives.
6. Create the backend's access key yourself, so the secret never appears in a chat: **AWS console → IAM →
   Users → prominder-backend → Security credentials → Create access key → "Application running outside
   AWS"**. Copy both values straight into Render (step 2 below).

Without the connector, run the script on your own computer instead (step 1).

## 1. Create the AWS resources

On your computer, logged in to AWS as an admin (`aws configure`, or set `AWS_PROFILE`):

```bash
cd backend
pip install boto3
python scripts/aws_setup.py --email you@example.com
# AWS emails you a verification link. Click it, then:
python scripts/aws_setup.py --email you@example.com --create-access-key
```

It's safe to run again. It prints every value the next steps need.

## 2. Switch over (about 15 minutes)

Do it in this order, so nothing the app writes during the switch is lost:

1. **Pause the timer.** cron-job.org → the `/tick` job → disable it. Don't chat with the app until step 6.
2. **Copy the data.** On your computer (only reads from Supabase):
   ```bash
   cd backend
   pip install -r requirements.txt "psycopg[binary]"
   export DATABASE_URL='<the current value on Render>'
   export AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=...     # the backend key from step 1
   export AWS_REGION=us-west-2 DYNAMODB_TABLE=prominder
   python scripts/migrate_from_postgres.py --dry-run          # shows the row counts
   python scripts/migrate_from_postgres.py
   ```
   Ids stay the same, so links in old notifications still work.
3. **Render → annoying-reminder-api → Environment:** add `AWS_REGION`, `DYNAMODB_TABLE`,
   `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `COGNITO_USER_POOL_ID`, `COGNITO_CLIENT_ID`, and replace
   `OWNER_USER_ID` with the new value (your Cognito user's id). Delete `DATABASE_URL`, `SUPABASE_URL`,
   `SUPABASE_ANON_KEY`.
4. **Render → annoying-reminder-web → Environment:** add `COGNITO_REGION` and `COGNITO_CLIENT_ID`, delete
   `SUPABASE_URL` and `SUPABASE_ANON_KEY`. Then deploy both services from the branch with this change
   (or merge it to `main`).
5. **GitHub → Settings → Secrets and variables → Actions → Variables** (for the Android app): add
   `COGNITO_REGION` and `COGNITO_CLIENT_ID`, delete `SUPABASE_URL` and `SUPABASE_ANON_KEY`.
6. **Log in again** on the web app and the phone (the old Supabase logins don't carry over). Check that
   your chat and tasks are there, then **turn the cron job back on**.

## If something goes wrong

The migration only reads from Supabase, so the old setup is untouched. To go back: put the old
environment values back on Render and deploy the previous commit. Once the new setup has worked for a
week or two, pause or delete the Supabase project.

## How the data is stored (for later changes)

One DynamoDB table, single-table design; the layout is at the top of
[`backend/app/db.py`](../backend/app/db.py). Changes that depend on the current state read the item,
change it in Python and write it back only if nobody wrote in between (a version number), so an
overlapping `/tick` still can't claim a task twice. Open tasks are read from an index and filtered with
the same rules as `logic.py`. The tests run the real storage code against moto (a fake DynamoDB):
`backend/tests/test_dynamo_store.py`.
