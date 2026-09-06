# ClickUp 동기화

`tasks/*.json` source는 `$task-graph` 규칙을 따릅니다.

루트 `.clickup-sync.json`에 ClickUp 설정을 저장합니다.

```sh
cp scripts/clickup/clickup-sync.example.json .clickup-sync.json
```

루트 `.env`에 API token을 저장합니다.

```text
CLICKUP_TOKEN=pk_...
```

셸에 이미 `CLICKUP_TOKEN`이 설정되어 있으면 해당 값을 우선 사용합니다.

```sh
node scripts/clickup/sync-task-graph.mjs sync --dry
node scripts/clickup/sync-task-graph.mjs sync
```

`sync --dry`는 변경 예정 사항만 출력합니다. `sync`는 변경을 반영하고 성공하면 ClickUp 확인 URL만 출력합니다.
