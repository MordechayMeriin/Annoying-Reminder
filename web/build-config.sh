#!/bin/sh
# Writes config.js from environment variables at build time (Render static site → Environment).
# These values are public by design (they end up in the browser); secrets never go here.
set -eu

: "${SUPABASE_URL:?Set SUPABASE_URL on the static site}"
: "${SUPABASE_ANON_KEY:?Set SUPABASE_ANON_KEY on the static site}"
: "${API_URL:?Set API_URL on the static site}"

cat > config.js <<EOF
// Generated at build time by build-config.sh — do not edit or commit.
window.APP_CONFIG = {
  SUPABASE_URL: "${SUPABASE_URL}",
  SUPABASE_ANON_KEY: "${SUPABASE_ANON_KEY}",
  API_URL: "${API_URL}",
};
EOF

echo "config.js written for API ${API_URL}"
