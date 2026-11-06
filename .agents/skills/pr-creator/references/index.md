# PR Creator 참고 파일 인덱스

| 원하는 작업 | 읽을 파일 | 핵심 기준 |
| --- | --- | --- |
| PR 작성·재구성 | [SKILL.md](../SKILL.md), [pr-template.md](../assets/pr-template.md) | 문제 → 변화 → 근거 → 영향 → 검증 → 질문 |
| 장문·개념 과밀 해소 | [visual-structure.md](visual-structure.md) | 의미별 서브헤딩, 비교표, 관계 다이어그램 |
| 문제 진단 및 자동 수정 | [problem-catalog.md](problem-catalog.md) | 확정 조건, 반례, 수정 방법, 재점검 |
| 품질 점수와 게이트 | [rubric.md](rubric.md) | 6개 축, FAIL은 총점으로 상쇄 금지 |
| 실제 아키텍처 사례 | [case-pr38.md](case-pr38.md) | BC·Slice·DB·API 영향을 한 문단에 섞지 않음 |
| 출처 확인 | [sources.md](sources.md) | 공식 지원 형식과 작성 원칙 구분 |
| 간단한 기계적 탐지 | [../scripts/check_pr.py](../scripts/check_pr.py) | 확정 위반이 아닌 구조 개선 신호만 추출 |

## 대표 진단 경로

- **읽기 어려운 PR**: `PRC-010`, `PRC-011`, `PRC-012` → 시각 구조 기준 → 독자가 놓칠 수 있는 위험의 보존 여부 확인.
- **Agent 결과보고형 PR**: `PRC-001`, `PRC-002`, `PRC-003` → 핵심 변경의 의사결정 가치 확인.
- **중요 정보 누락**: `PRC-004`, `PRC-005`, `PRC-007`, `PRC-013` → Diff·실제 검증과 대응.
- **검증이 부정확한 PR**: `PRC-006`, `PRC-008`, `PRC-014` → 확인 결과와 보장 범위를 좁혀 작성.
- **문서·정책 변경**: `PRC-009` → 신규 규칙과 단순 표현 수정을 분리.