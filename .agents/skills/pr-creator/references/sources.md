# 근거 자료와 적용 범위

## 공식 지침

- [Google Engineering Practices: Writing good CL descriptions](https://google.github.io/eng-practices/review/developer/cl-descriptions.html): 무엇을 변경하고 왜 변경하는지, 코드만 읽어도 알 수 없는 맥락을 남기도록 권장한다.
- [GitHub Docs: Helping others review your changes](https://docs.github.com/en/pull-requests/concepts/helping-others-review-your-changes): 문제·접근 방식·결과, 집중 검토할 부분을 전달하도록 안내한다.
- [GitHub Docs: Organizing information with tables](https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/organizing-information-with-tables): PR 본문에서 Markdown 표를 사용할 수 있다.
- [GitHub Docs: Creating diagrams](https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/creating-diagrams): PR Markdown에서 Mermaid 다이어그램을 렌더링할 수 있다.
- [GitHub Docs: Style guide, Tables](https://docs.github.com/en/contributing/style-guide-and-content-model/style-guide): 표는 비교나 다속성 정보를 전달할 때 유리하며 단순 목록을 표로 만들지 말도록 안내한다. 이는 GitHub Docs 자체의 스타일 가이드로, 모든 PR에 강제되는 규칙은 아니다.

## 스킬 자체 판단 규칙

- 복잡한 책임·계약 변화가 하나의 문단에 섞이면 서브헤딩으로 분리한다.
- 전후 비교는 표, 복잡한 협력·호출 흐름은 다이어그램을 우선 검토한다.
- 중요한 호환성·데이터 영향은 상세 기술 설명 앞에서 알린다.
- 자료의 정확성을 별도로 검사하고, 불확실한 내용을 시각 자료로 확정하지 않는다.
- 심각한 누락·검증 허위·중요 위험 누락은 총점으로 상쇄하지 않는다.

이 규칙들은 위 공식 자료를 바탕으로 SpringKit PR #18·#27·#38·#42의 검토 경험에서 정리한 **해당 스킬의 운영 기준**이다. 보편적 업계 표준 수치로 제시하지 않는다.