"""
Create (or check) everything ProMinder needs in your AWS account. Safe to run again: it finds what
already exists and only adds what's missing.

    cd backend
    pip install boto3
    # Logged in to AWS as an admin (aws configure / AWS_PROFILE, or the keys in the environment):
    python scripts/aws_setup.py --email you@example.com
    # → the first run sends an email from AWS ("Amazon Web Services – Email Address Verification
    #   Request"). Click the link, then run the same command again.
    python scripts/aws_setup.py --email you@example.com --create-access-key

What it creates (all within the always-free limits for one user):
  1. An SES email identity for your address. Cognito sends the login codes through it (sending
     one-time codes by email requires SES). SES starts in its "sandbox": it can only send to verified
     addresses, which is fine, because the only recipient is you.
  2. The DynamoDB table (provisioned capacity adding up to the free 25 read / 25 write units).
  3. A Cognito user pool (Essentials plan: free up to 10,000 monthly users) that logs in with a code
     sent by email, sign-up turned off, and your user in it. An app client for the web and Android
     apps (no secret; logins last 10 years unless you log out).
  4. An IAM user "prominder-backend" that can only read and write this table. --create-access-key
     prints a key for it (put it on Render; it's shown once).
  5. A monthly budget of $1 that emails you if AWS ever expects to charge anything (--no-budget skips it).

At the end it prints the values for Render (API and web) and the Android build.
"""

import argparse
import json
import sys
from pathlib import Path

import boto3
from botocore.exceptions import ClientError

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.db import table_spec  # noqa: E402

POOL_NAME = "prominder"
CLIENT_NAME = "prominder-apps"
IAM_USER = "prominder-backend"
BUDGET_NAME = "prominder-free-tier-guard"


def step(text: str) -> None:
    print(f"\n== {text}")


def ses_identity(ses, email: str) -> bool:
    """True when the address is verified for sending."""
    try:
        r = ses.get_email_identity(EmailIdentity=email)
    except ses.exceptions.NotFoundException:
        ses.create_email_identity(EmailIdentity=email)
        print(f"Sent a verification email to {email}. Click the link in it, then run this again.")
        return False
    if r.get("VerifiedForSendingStatus"):
        print(f"{email} is verified for sending.")
        return True
    print(f"{email} isn't verified yet. Click the link in AWS's email (check spam), then run this again.")
    return False


def dynamodb_table(ddb, name: str) -> str:
    try:
        r = ddb.describe_table(TableName=name)
        print(f"Table {name} exists ({r['Table']['TableStatus']}).")
        return r["Table"]["TableArn"]
    except ddb.exceptions.ResourceNotFoundException:
        pass
    r = ddb.create_table(**table_spec(name))
    print(f"Creating table {name}…")
    ddb.get_waiter("table_exists").wait(TableName=name)
    print("Created.")
    return r["TableDescription"]["TableArn"]


def user_pool(cog, region: str, account: str, email: str) -> str:
    for page in cog.get_paginator("list_user_pools").paginate(MaxResults=60):
        for p in page["UserPools"]:
            if p["Name"] == POOL_NAME:
                print(f"User pool {POOL_NAME} exists ({p['Id']}).")
                return p["Id"]
    r = cog.create_user_pool(
        PoolName=POOL_NAME,
        UserPoolTier="ESSENTIALS",
        DeletionProtection="ACTIVE",
        UsernameAttributes=["email"],
        UsernameConfiguration={"CaseSensitive": False},
        AutoVerifiedAttributes=["email"],
        MfaConfiguration="OFF",
        # Nobody can sign up: only users created here (you) can log in.
        AdminCreateUserConfig={"AllowAdminCreateUserOnly": True},
        Policies={"SignInPolicy": {"AllowedFirstAuthFactors": ["PASSWORD", "EMAIL_OTP"]}},
        AccountRecoverySetting={"RecoveryMechanisms": [{"Priority": 1, "Name": "verified_email"}]},
        EmailConfiguration={
            "EmailSendingAccount": "DEVELOPER",
            "SourceArn": f"arn:aws:ses:{region}:{account}:identity/{email}",
            "From": f"ProMinder <{email}>",
        },
    )
    pool_id = r["UserPool"]["Id"]
    print(f"Created user pool {pool_id}.")
    return pool_id


def app_client(cog, pool_id: str) -> str:
    for page in cog.get_paginator("list_user_pool_clients").paginate(UserPoolId=pool_id, MaxResults=60):
        for c in page["UserPoolClients"]:
            if c["ClientName"] == CLIENT_NAME:
                print(f"App client exists ({c['ClientId']}).")
                return c["ClientId"]
    r = cog.create_user_pool_client(
        UserPoolId=pool_id,
        ClientName=CLIENT_NAME,
        GenerateSecret=False,   # the apps can't keep a secret
        ExplicitAuthFlows=["ALLOW_USER_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"],
        AccessTokenValidity=60, IdTokenValidity=60, RefreshTokenValidity=3650,
        TokenValidityUnits={"AccessToken": "minutes", "IdToken": "minutes", "RefreshToken": "days"},
        AuthSessionValidity=15,   # minutes to type the emailed code (the longest Cognito allows)
        PreventUserExistenceErrors="ENABLED",
        EnableTokenRevocation=True,
    )
    client_id = r["UserPoolClient"]["ClientId"]
    print(f"Created app client {client_id}.")
    return client_id


def owner(cog, pool_id: str, email: str) -> str:
    try:
        r = cog.admin_get_user(UserPoolId=pool_id, Username=email)
        attrs = r["UserAttributes"]
        print(f"User {email} exists.")
    except cog.exceptions.UserNotFoundException:
        r = cog.admin_create_user(
            UserPoolId=pool_id, Username=email, MessageAction="SUPPRESS",
            UserAttributes=[{"Name": "email", "Value": email}, {"Name": "email_verified", "Value": "true"}])
        attrs = r["User"]["Attributes"]
        print(f"Created user {email}.")
    return next(a["Value"] for a in attrs if a["Name"] == "sub")


def backend_user(iam, table_arn: str, create_key: bool) -> tuple[str, str] | None:
    try:
        iam.get_user(UserName=IAM_USER)
        print(f"IAM user {IAM_USER} exists.")
    except iam.exceptions.NoSuchEntityException:
        iam.create_user(UserName=IAM_USER)
        print(f"Created IAM user {IAM_USER}.")
    policy = {
        "Version": "2012-10-17",
        "Statement": [{
            "Effect": "Allow",
            "Action": ["dynamodb:GetItem", "dynamodb:BatchGetItem", "dynamodb:Query", "dynamodb:PutItem",
                       "dynamodb:UpdateItem", "dynamodb:DeleteItem", "dynamodb:BatchWriteItem",
                       "dynamodb:ConditionCheckItem", "dynamodb:DescribeTable"],
            "Resource": [table_arn, f"{table_arn}/index/*"],
        }],
    }
    iam.put_user_policy(UserName=IAM_USER, PolicyName="prominder-table", PolicyDocument=json.dumps(policy))
    print("Its permissions: this table only.")
    if not create_key:
        return None
    keys = iam.list_access_keys(UserName=IAM_USER)["AccessKeyMetadata"]
    if len(keys) >= 2:
        print("It already has 2 access keys (the most allowed). Delete an old one in the IAM console first.")
        return None
    k = iam.create_access_key(UserName=IAM_USER)["AccessKey"]
    return k["AccessKeyId"], k["SecretAccessKey"]


def budget(budgets, account: str, email: str) -> None:
    try:
        budgets.describe_budget(AccountId=account, BudgetName=BUDGET_NAME)
        print("The $1 budget alert exists.")
        return
    except budgets.exceptions.NotFoundException:
        pass
    notifications = [
        {"Notification": {"NotificationType": kind, "ComparisonOperator": "GREATER_THAN", "Threshold": 1.0,
                          "ThresholdType": "PERCENTAGE"},
         "Subscribers": [{"SubscriptionType": "EMAIL", "Address": email}]}
        for kind in ("ACTUAL", "FORECASTED")
    ]
    budgets.create_budget(
        AccountId=account,
        Budget={"BudgetName": BUDGET_NAME, "BudgetLimit": {"Amount": "1", "Unit": "USD"},
                "TimeUnit": "MONTHLY", "BudgetType": "COST"},
        NotificationsWithSubscribers=notifications)
    print(f"Created a $1/month budget: AWS emails {email} if it ever expects a charge.")


def main() -> None:
    ap = argparse.ArgumentParser(description="Set up ProMinder's AWS resources.")
    ap.add_argument("--email", required=True, help="your email: the login, and the sender of the login codes")
    ap.add_argument("--region", default="us-west-2",
                    help="AWS region (default us-west-2, Oregon: next to Render's default region)")
    ap.add_argument("--table", default="prominder")
    ap.add_argument("--create-access-key", action="store_true", help="print a new access key for the backend")
    ap.add_argument("--no-budget", action="store_true")
    a = ap.parse_args()
    email = a.email.strip().lower()
    session = boto3.session.Session(region_name=a.region)
    account = session.client("sts").get_caller_identity()["Account"]
    print(f"AWS account {account}, region {a.region}")

    step("1. Login email (SES)")
    if not ses_identity(session.client("sesv2"), email):
        sys.exit(0)
    step("2. Database (DynamoDB)")
    table_arn = dynamodb_table(session.client("dynamodb"), a.table)
    step("3. Login (Cognito)")
    cog = session.client("cognito-idp")
    pool_id = user_pool(cog, a.region, account, email)
    client_id = app_client(cog, pool_id)
    owner_id = owner(cog, pool_id, email)
    step("4. Backend access (IAM)")
    key = backend_user(session.client("iam"), table_arn, a.create_access_key)
    if not a.no_budget:
        step("5. Cost alert (Budgets)")
        try:
            budget(session.client("budgets", region_name="us-east-1"), account, email)
        except ClientError as e:
            print(f"Couldn't create the budget ({e.response['Error']['Code']}); add one in the Billing console.")

    step("Done. Values to set")
    print("Render → annoying-reminder-api → Environment:")
    print(f"  AWS_REGION={a.region}\n  DYNAMODB_TABLE={a.table}\n  COGNITO_USER_POOL_ID={pool_id}\n"
          f"  COGNITO_CLIENT_ID={client_id}\n  OWNER_USER_ID={owner_id}")
    if key:
        print(f"  AWS_ACCESS_KEY_ID={key[0]}\n  AWS_SECRET_ACCESS_KEY={key[1]}   ← shown only now")
    else:
        print("  AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY: run again with --create-access-key "
              "(or create one for the IAM user prominder-backend in the console)")
    print("Render → annoying-reminder-web → Environment (and GitHub → Settings → Variables for Android):")
    print(f"  COGNITO_REGION={a.region}\n  COGNITO_CLIENT_ID={client_id}")
    print("Then remove DATABASE_URL, SUPABASE_URL and SUPABASE_ANON_KEY.")


if __name__ == "__main__":
    try:
        main()
    except ClientError as e:
        sys.exit(f"AWS said no: {e.response['Error']['Code']}: {e.response['Error']['Message']}")
