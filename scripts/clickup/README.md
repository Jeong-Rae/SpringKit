# ClickUp 동기화

`tasks/*.json` source는 `$task-graph` 규칙을 따릅니다.

ClickUp List에 Short Text Custom Field 두 개를 만들고 ID를 config에 지정합니다.

- `source_id_field_id`: Task와 Step의 ID 저장
- `source_type_field_id`: Task의 type 저장

API token은 config에 저장하지 않고 `CLICKUP_TOKEN` 환경 변수로 전달합니다.

```sh
cp scripts/clickup/clickup-sync.example.json /tmp/clickup-sync.json
export CLICKUP_TOKEN=pk_...
node scripts/clickup/sync-task-graph.mjs sync --dry --config /tmp/clickup-sync.json
node scripts/clickup/sync-task-graph.mjs sync --config /tmp/clickup-sync.json
```

`sync --dry`는 변경 예정 사항만 출력합니다. `sync`는 변경을 반영하고 성공하면 ClickUp 확인 URL만 출력합니다.
