#!/usr/bin/env bash
set -euo pipefail

pwd > "$APP_STUB_CWD_PATH"
cat > "$APP_STUB_CAPTURE_PATH"
cat "$APP_STUB_STDOUT_PATH"
cat "$APP_STUB_STDERR_PATH" >&2
exit "$APP_STUB_EXIT_CODE"
