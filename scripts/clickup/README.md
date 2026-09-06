# ClickUp downstream 동기화

`tasks/*.json` source는 `$task-graph` 규칙을 따릅니다.

루트 `config/task-sync.config.json`에 downstream target별 설정을 저장합니다. 현재 지원하는 target은 ClickUp입니다.

```sh
mkdir -p config
cp scripts/clickup/task-sync.config.example.json config/task-sync.config.json
```

`config/.env.example`을 `config/.env.local`로 복사하고 API token을 설정합니다.

```sh
cp config/.env.example config/.env.local
```

셸에 이미 `CLICKUP_TOKEN`이 설정되어 있으면 파일의 값보다 우선 사용합니다.

```sh
node scripts/clickup/sync-task-graph.mjs sync --dry
node scripts/clickup/sync-task-graph.mjs sync
```

`sync --dry`는 변경 예정 사항만 출력합니다. `sync`는 변경을 반영하고 성공하면 ClickUp 확인 URL만 출력합니다.
