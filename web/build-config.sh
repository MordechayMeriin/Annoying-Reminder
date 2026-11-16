#!/bin/sh
# Writes config.js from environment variables at build time (Render static site → Environment).
# These values are public by design (they end up in the browser); secrets never go here.
set -eu

: "${COGNITO_REGION:?Set COGNITO_REGION on the static site}"
: "${COGNITO_CLIENT_ID:?Set COGNITO_CLIENT_ID on the static site}"
: "${API_URL:?Set API_URL on the static site}"

cat > config.js <<EOF
// Generated at build time by build-config.sh — do not edit or commit.
window.APP_CONFIG = {
  COGNITO_REGION: "${COGNITO_REGION}",
  COGNITO_CLIENT_ID: "${COGNITO_CLIENT_ID}",
  API_URL: "${API_URL}",
};
EOF

echo "config.js written for API ${API_URL}"
