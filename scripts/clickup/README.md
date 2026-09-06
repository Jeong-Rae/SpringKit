# Task Graph ClickUp Sync

`tasks/*.json`을 읽기 전용 source로 사용하여 ClickUp List에 Task, Subtask와 direct dependency를 단방향으로 동기화합니다. `.agents/skills/task-graph`는 수정하거나 호출하지 않습니다.

ClickUp List에는 text 계열 Custom Field 두 개를 미리 만들고 ID를 config에 지정합니다.

- `source_id_field_id`: `sk-5`, `sk-5-1` 같은 source identity
- `source_type_field_id`: Task의 `feature`, `fix`, `refactor`, `ci`, `dep`

API token은 config에 저장하지 않고 `CLICKUP_TOKEN` 환경 변수로 전달합니다.

```sh
cp scripts/clickup/clickup-sync.example.json /tmp/clickup-sync.json
export CLICKUP_TOKEN=pk_...
node scripts/clickup/sync-task-graph.mjs plan --config /tmp/clickup-sync.json
node scripts/clickup/sync-task-graph.mjs apply --config /tmp/clickup-sync.json
```

`plan`은 ClickUp을 읽기만 합니다. `apply`는 동일한 preflight와 diff를 계산한 뒤 managed field만 변경합니다. Source에서 사라진 Task/Subtask와 unmanaged ClickUp field/dependency는 삭제하지 않습니다.
