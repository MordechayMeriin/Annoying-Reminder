"""
Move the backend from Render to AWS Lambda (Android Phase C). Run once, as an admin of your AWS account. Safe to
run again: it finds what exists and only adds or updates what's missing.

The easiest place is AWS CloudShell (the >_ icon at the top of the AWS console, in the region of your DynamoDB
table, the same as AWS_REGION on Render). It has Python, boto3 and your login:

    git clone https://github.com/MordechayMeriin/Annoying-Reminder.git && cd Annoying-Reminder/backend
    python3 scripts/aws_lambda_setup.py
    # It asks for the values from Render → annoying-reminder-api → Environment: GOOGLE_CLIENT_ID,
    # OWNER_GOOGLE_EMAIL, ACTION_SECRET, and (Phase D, the assistant) OPENROUTER_API_KEY and OPENROUTER_MODELS.
    # Secrets are typed, not shown. Run again, it only asks for what the function doesn't have yet.

What it creates (all inside the always-free limits for one user):
  1. A role for the function "prominder-api": this table only, sending email from your address, its own logs.
  2. The function itself (Python 3.12), with a stand-in until GitHub Actions deploys the real code, and its
     settings. ACTION_SECRET is Render's, so the phone stays logged in. The OpenRouter key lives here, never on
     the phone (Phase D).
  3. Its HTTPS address (a "function URL"). The app checks every login itself, as on Render.
  4. The daily "is the phone still alive?" run (EventBridge, 06:00 UTC).
  5. GitHub's way in (OIDC): a role GitHub Actions can take, from this repository's main branch only, that
     may only update this function's code. No AWS keys are stored in GitHub.
At the end it prints the three GitHub variables to set.
"""

import argparse
import getpass
import io
import json
import os
import sys
import time
import urllib.request
import zipfile
from pathlib import Path

import boto3
from botocore.exceptions import ClientError

sys.path.insert(0, str(Path(__file__).resolve().parent))

from aws_setup import ses_identity  # noqa: E402

FUNCTION = "prominder-api"
ROLE = "prominder-lambda"
DEPLOY_ROLE = "prominder-github-deploy"
RULE = "prominder-alive-check"
SCHEDULE = "cron(0 6 * * ? *)"   # 06:00 UTC: 08:00 in Israel's winter, 09:00 in summer
GITHUB_OIDC = "token.actions.githubusercontent.com"
TIMEOUT_S = 60   # one model call takes up to LLM_TIMEOUT_S (25 s), plus a cold start

# Until the first deploy: answers 503, so the app says "the server isn't ready" instead of something stranger.
STAND_IN = (
    "import json\n"
    "def handler(event, context):\n"
    "    return {'statusCode': 503, 'headers': {'content-type': 'application/json'},\n"
    "            'body': json.dumps({'detail': 'Being deployed: GitHub Actions puts the real code here.'})}\n"
)


def step(text: str) -> None:
    print(f"\n== {text}")


def ask(name: str, given: str | None, secret: bool = False, current: dict[str, str] | None = None) -> str:
    """A value from the command line, the environment, the function's current settings, or typed in."""
    value = (given or os.environ.get(name, "") or (current or {}).get(name, "")).strip()
    if value == "unused-on-lambda":
        value = ""
    while not value:
        value = (getpass.getpass if secret else input)(f"{name} (from Render → annoying-reminder-api → Environment): ").strip()
    return value


def current_env(lam) -> dict[str, str]:
    """The function's settings as they are (empty before it exists): a re-run asks only for what's missing."""
    try:
        return lam.get_function_configuration(FunctionName=FUNCTION).get("Environment", {}).get("Variables", {})
    except lam.exceptions.ResourceNotFoundException:
        return {}


def lambda_role(iam, table_arn: str, email_arn: str) -> str:
    trust = {"Version": "2012-10-17", "Statement": [
        {"Effect": "Allow", "Principal": {"Service": "lambda.amazonaws.com"}, "Action": "sts:AssumeRole"}]}
    try:
        arn = iam.get_role(RoleName=ROLE)["Role"]["Arn"]
        print(f"Role {ROLE} exists.")
    except iam.exceptions.NoSuchEntityException:
        arn = iam.create_role(RoleName=ROLE, AssumeRolePolicyDocument=json.dumps(trust),
                              Description="ProMinder's Lambda function: its table, its email, its logs")["Role"]["Arn"]
        print(f"Created role {ROLE}.")
        time.sleep(10)   # a new role takes a few seconds before Lambda may use it
    iam.attach_role_policy(RoleName=ROLE, PolicyArn="arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole")
    policy = {"Version": "2012-10-17", "Statement": [
        {"Effect": "Allow",
         "Action": ["dynamodb:GetItem", "dynamodb:BatchGetItem", "dynamodb:Query", "dynamodb:PutItem",
                    "dynamodb:UpdateItem", "dynamodb:DeleteItem", "dynamodb:BatchWriteItem",
                    "dynamodb:ConditionCheckItem", "dynamodb:DescribeTable"],
         "Resource": [table_arn, f"{table_arn}/index/*"]},
        {"Effect": "Allow", "Action": ["ses:SendEmail"], "Resource": [email_arn]},
    ]}
    iam.put_role_policy(RoleName=ROLE, PolicyName="prominder-table-and-email", PolicyDocument=json.dumps(policy))
    print("Its permissions: this table, email from your address, its logs.")
    return arn


def function(lam, role_arn: str, env: dict[str, str]) -> str:
    try:
        cfg = lam.get_function_configuration(FunctionName=FUNCTION)
        merged = {**cfg.get("Environment", {}).get("Variables", {}), **env}
        lam.update_function_configuration(FunctionName=FUNCTION, Role=role_arn, Environment={"Variables": merged},
                                          Timeout=TIMEOUT_S, MemorySize=512)
        print(f"Function {FUNCTION} exists: settings updated.")
        return cfg["FunctionArn"]
    except lam.exceptions.ResourceNotFoundException:
        pass
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("app/__init__.py", "")
        z.writestr("app/lambda_app.py", STAND_IN)
    for attempt in range(6):
        try:
            arn = lam.create_function(
                FunctionName=FUNCTION, Runtime="python3.12", Role=role_arn, Handler="app.lambda_app.handler",
                Code={"ZipFile": buf.getvalue()}, Timeout=TIMEOUT_S, MemorySize=512, Architectures=["x86_64"],
                Environment={"Variables": env}, Description="ProMinder: Google sign-in, the phone's backup, the assistant's model calls, the alive check",
            )["FunctionArn"]
            print(f"Created function {FUNCTION} (a stand-in until GitHub Actions deploys the code).")
            lam.get_waiter("function_active_v2").wait(FunctionName=FUNCTION)
            return arn
        except lam.exceptions.InvalidParameterValueException as e:
            if "role" not in str(e).lower() or attempt == 5:
                raise
            time.sleep(5)   # the new role isn't usable yet
    raise RuntimeError("unreachable")


def _permission(lam, sid: str, **kw) -> None:
    try:
        lam.add_permission(FunctionName=FUNCTION, StatementId=sid, **kw)
    except lam.exceptions.ResourceConflictException:
        pass   # already there


def function_url(lam) -> str:
    try:
        url = lam.get_function_url_config(FunctionName=FUNCTION)["FunctionUrl"]
        print("Function URL exists.")
    except lam.exceptions.ResourceNotFoundException:
        url = lam.create_function_url_config(FunctionName=FUNCTION, AuthType="NONE")["FunctionUrl"]
        print("Created the function URL.")
    # Anyone may reach the URL; the app's own login check (auth.py) decides who gets in, as on Render.
    _permission(lam, "public-url", Action="lambda:InvokeFunctionUrl", Principal="*", FunctionUrlAuthType="NONE")
    try:
        _permission(lam, "public-url-invoke", Action="lambda:InvokeFunction", Principal="*", InvokedViaFunctionUrl=True)
    except Exception:   # older boto3 without InvokedViaFunctionUrl: the first permission is enough there
        pass
    return url.rstrip("/")


def schedule(events, lam, function_arn: str) -> None:
    rule_arn = events.put_rule(Name=RULE, ScheduleExpression=SCHEDULE, State="ENABLED",
                               Description="ProMinder: email if the phone hasn't backed up for 2 days")["RuleArn"]
    events.put_targets(Rule=RULE, Targets=[{"Id": "prominder-api", "Arn": function_arn}])
    _permission(lam, "daily-alive-check", Action="lambda:InvokeFunction", Principal="events.amazonaws.com", SourceArn=rule_arn)
    print(f"The alive check runs daily ({SCHEDULE}, UTC).")


def github_subjects(repo: str) -> list[str]:
    """
    Who GitHub says is asking ("sub"), as the role allows it: this repository's main branch. GitHub now writes the
    owner's and the repository's permanent ids into it (repo:Owner@123/Name@456:ref:refs/heads/main), which also
    keeps out a repository of the same name made later. The ids come from GitHub's public API; if it can't be
    reached, any id is accepted for this owner and name. The older form without ids is kept, in both spellings
    (IAM compares case-sensitively).
    """
    owner, name = repo.split("/", 1)
    subjects = {f"repo:{repo}:ref:refs/heads/main", f"repo:{repo.lower()}:ref:refs/heads/main"}
    try:
        with urllib.request.urlopen(f"https://api.github.com/repos/{repo}", timeout=10) as r:
            info = json.load(r)
        subjects.add(f"repo:{info['owner']['login']}@{info['owner']['id']}/{info['name']}@{info['id']}:ref:refs/heads/main")
        print(f"GitHub ids: {info['owner']['login']} = {info['owner']['id']}, {info['name']} = {info['id']}.")
    except Exception as e:   # no internet, rate limit: accept any id for this owner and name
        print(f"Couldn't ask GitHub for the ids ({type(e).__name__}); allowing any id for {repo}.")
        subjects.add(f"repo:{owner}@*/{name}@*:ref:refs/heads/main")
    return sorted(subjects)


def github_deploy_role(iam, account: str, repo: str, function_arn: str) -> str:
    provider_arn = f"arn:aws:iam::{account}:oidc-provider/{GITHUB_OIDC}"
    try:
        existing = iam.get_open_id_connect_provider(OpenIDConnectProviderArn=provider_arn)
        print("GitHub's OIDC provider exists.")
        # One made earlier (by hand, or for another project) may not accept AWS as the audience: add it.
        if "sts.amazonaws.com" not in existing.get("ClientIDList", []):
            iam.add_client_id_to_open_id_connect_provider(OpenIDConnectProviderArn=provider_arn, ClientID="sts.amazonaws.com")
            print("Added sts.amazonaws.com to its audiences (it was missing).")
    except iam.exceptions.NoSuchEntityException:
        iam.create_open_id_connect_provider(Url=f"https://{GITHUB_OIDC}", ClientIDList=["sts.amazonaws.com"],
                                            ThumbprintList=["6938fd4d98bab03faadb97b34396831e3780aea1"])
        print("Added GitHub's OIDC provider.")
    trust = {"Version": "2012-10-17", "Statement": [{
        "Effect": "Allow", "Principal": {"Federated": provider_arn}, "Action": "sts:AssumeRoleWithWebIdentity",
        # Only this repository's main branch (github_subjects).
        "Condition": {"StringEquals": {f"{GITHUB_OIDC}:aud": "sts.amazonaws.com"},
                      "StringLike": {f"{GITHUB_OIDC}:sub": github_subjects(repo)}},
    }]}
    try:
        arn = iam.get_role(RoleName=DEPLOY_ROLE)["Role"]["Arn"]
        iam.update_assume_role_policy(RoleName=DEPLOY_ROLE, PolicyDocument=json.dumps(trust))
        print(f"Role {DEPLOY_ROLE} exists: who may use it was updated.")
    except iam.exceptions.NoSuchEntityException:
        arn = iam.create_role(RoleName=DEPLOY_ROLE, AssumeRolePolicyDocument=json.dumps(trust),
                              Description=f"GitHub Actions ({repo}, main) deploys ProMinder's Lambda code")["Role"]["Arn"]
        print(f"Created role {DEPLOY_ROLE}.")
    policy = {"Version": "2012-10-17", "Statement": [{
        "Effect": "Allow",
        "Action": ["lambda:UpdateFunctionCode", "lambda:GetFunction", "lambda:GetFunctionConfiguration",
                   "lambda:GetFunctionUrlConfig"],
        "Resource": function_arn,
    }]}
    iam.put_role_policy(RoleName=DEPLOY_ROLE, PolicyName="deploy-prominder-api", PolicyDocument=json.dumps(policy))
    print("Its permissions: update this function's code, nothing else.")
    return arn


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--region", default=os.environ.get("AWS_REGION") or boto3.session.Session().region_name or "us-west-2",
                   help="the region of the DynamoDB table (CloudShell's region by default)")
    p.add_argument("--table", default="prominder")
    p.add_argument("--repo", default="MordechayMeriin/Annoying-Reminder", help="owner/name on GitHub")
    p.add_argument("--google-client-id")
    p.add_argument("--owner-email", help="OWNER_GOOGLE_EMAIL")
    p.add_argument("--alert-email", help="where the 'phone is silent' email goes (default: the owner's address)")
    p.add_argument("--openrouter-models", help="OPENROUTER_MODELS: comma-separated, in fallback order")
    a = p.parse_args()

    session = boto3.session.Session(region_name=a.region)
    account = session.client("sts").get_caller_identity()["Account"]
    iam, lam = session.client("iam"), session.client("lambda")
    have = current_env(lam)

    google_client_id = ask("GOOGLE_CLIENT_ID", a.google_client_id, current=have)
    owner = ask("OWNER_GOOGLE_EMAIL", a.owner_email, current=have).lower()
    action_secret = ask("ACTION_SECRET", None, secret=True, current=have)
    openrouter_key = ask("OPENROUTER_API_KEY", None, secret=True, current=have)
    openrouter_models = ask("OPENROUTER_MODELS", a.openrouter_models, current=have)
    alert = (a.alert_email or have.get("ALERT_EMAIL") or owner).lower()

    step(f"The table ({a.region})")
    table_arn = session.client("dynamodb").describe_table(TableName=a.table)["Table"]["TableArn"]
    print(f"Found {a.table}.")

    step("The address the alive check emails from and to")
    if not ses_identity(session.client("sesv2"), alert):
        print("(The rest goes ahead; the email works once the address is verified.)")
    email_arn = f"arn:aws:ses:{a.region}:{account}:identity/{alert}"

    step("The function's role")
    role_arn = lambda_role(iam, table_arn, email_arn)

    step("The function")
    env = {"DYNAMODB_TABLE": a.table, "GOOGLE_CLIENT_ID": google_client_id, "OWNER_GOOGLE_EMAIL": owner,
           "ACTION_SECRET": action_secret, "ALERT_EMAIL": alert,
           "OPENROUTER_API_KEY": openrouter_key, "OPENROUTER_MODELS": openrouter_models}
    function_arn = function(lam, role_arn, env)

    step("Its address")
    url = function_url(lam)

    step("The daily alive check")
    schedule(session.client("events"), lam, function_arn)

    step("GitHub Actions' way in")
    deploy_arn = github_deploy_role(iam, account, a.repo, function_arn)

    step("Done. Now in GitHub → the repository → Settings → Secrets and variables → Actions → Variables:")
    print(f"  AWS_DEPLOY_ROLE_ARN = {deploy_arn}")
    print(f"  AWS_REGION          = {a.region}")
    print(f"  API_URL             = {url}      ← only after the check in docs/android-phase-c.md")
    print("Then run the 'Backend' workflow (Actions → Backend → Run workflow) to deploy the code.")


if __name__ == "__main__":
    try:
        main()
    except ClientError as e:
        sys.exit(f"AWS said no: {e.response['Error']['Code']}: {e.response['Error']['Message']}")
