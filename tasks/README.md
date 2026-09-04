# SpringKit Task DAG

이 디렉터리는 `spring-initializr-init-internals.md`에서 추출한 개인용 initializer 구현을 배포 가능한 Task로 나눈다.

## 실행 원칙

- 각 Task는 하나의 Goal만 가진다.
- 각 Step은 구현 직후 독립적으로 검증하고 커밋한다.
- 후속 Task는 모든 선행 Task가 완료된 뒤 시작한다.
- 서로 독립적인 Task를 병렬 수행할 때는 `.worktree` 아래의 별도 worktree를 사용한다.
- feature와 PR에는 각 문서에 적힌 Task ID를 그대로 사용한다.
- 커밋 제목과 본문은 유형 keyword 없이 한글로 작성한다. 코드 식별자와 고유명사는 예외다.

## Kotlin-first 구현 원칙

- SpringKit의 내부 구현 언어는 Kotlin으로 고정한다.
- 참고 자료나 기존 spec이 Java 형태의 class, interface, static utility를 제시하더라도 동일한 contract와 행동을 보존할 수 있다면 Kotlin-native 형태를 우선한다.
- 대체가 가능한 경우 `data class`, `value class`, `fun interface`, `sealed interface`, top-level function, extension function과 불변 collection을 사용한다.
- Kotlin-native 변환은 기존 spec의 검증 가능한 행동과 경계를 바꾸지 않는다. Java interoperability나 framework contract로 Java 형태가 필수인 경우에만 예외를 허용한다.
- 이 원칙은 Java 중심으로 작성된 기존 Task spec의 구현 형태보다 우선한다.

## Namespace 원칙

- 소유 도메인과 사용자 표시 주소의 기본값은 `jeongrae.me`다.
- JVM group과 package root는 역도메인 `me.jeongrae`를 사용한다.
- SpringKit 자체의 기본 package는 `me.jeongrae.springkit`이다.

## DAG

```mermaid
graph TD
    SK1[sk-1: 최소 실행형 생성기] --> SK2[sk-2: Canonical Description]
    SK2 --> SK3[sk-3: Convention 정책]
    SK3 --> SK4[sk-4: Generation Plan]
    SK4 --> SK5[sk-5: 기본 Contributor]
    SK5 --> SK6[sk-6: Dependency 정책]
    SK4 --> SK7[sk-7: 요청별 Context]
    SK6 --> SK8[sk-8: CLI Adapter]
    SK7 --> SK8
    SK8 --> SK9[sk-9: Archive 패키징]
```

## Task 목록

| Task | Goal | 선행 Task |
|---|---|---|
| [sk-1](sk-1.md) | 고정된 최소 프로젝트를 생성하는 실행 가능한 애플리케이션 제공 | 없음 |
| [sk-2](sk-2.md) | 외부 입력과 생성 코어 사이에 canonical description 경계 도입 | sk-1 |
| [sk-3](sk-3.md) | sparse input을 완전한 description으로 확장 | sk-2 |
| [sk-4](sk-4.md) | 최종 description으로 결정적인 generation plan 구성 | sk-3 |
| [sk-5](sk-5.md) | 실제로 빌드 가능한 프로젝트 파일 생성 | sk-4 |
| [sk-6](sk-6.md) | dependency 식별자 해석과 호환성 정책 중앙화 | sk-5 |
| [sk-7](sk-7.md) | 생성 요청별 상태와 수명주기 격리 | sk-4 |
| [sk-8](sk-8.md) | 생성 코어를 CLI 사용자 인터페이스로 제공 | sk-6, sk-7 |
| [sk-9](sk-9.md) | 생성 결과를 재현 가능한 ZIP archive로 제공 | sk-8 |

`sk-6`과 `sk-7`은 서로 독립적이며 선행 조건이 충족되면 병렬 수행할 수 있다.
