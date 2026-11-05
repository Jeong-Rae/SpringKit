#!/usr/bin/env bash
set -u

app_executable="${AGENT_GUIDEBOOK_APP_EXECUTABLE-}"

if [[ -z "$app_executable" ]]; then
  printf '%s\n' '앱 실행 파일 경로가 설정되지 않았습니다.' >&2
  exit 64
fi

if [[ "$app_executable" != /* ]]; then
  printf '%s\n' '앱 실행 파일 경로는 절대 경로여야 합니다.' >&2
  exit 64
fi

if [[ ! -e "$app_executable" ]]; then
  printf '%s\n' '앱 실행 파일을 찾을 수 없습니다.' >&2
  exit 127
fi

if [[ ! -f "$app_executable" || ! -x "$app_executable" ]]; then
  printf '%s\n' '앱 실행 파일을 실행할 수 없습니다.' >&2
  exit 126
fi

exec "$app_executable"
