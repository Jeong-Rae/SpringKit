# 테스트 작성 지침

테스트는 실행 조건과 기대 결과를 한 번에 이해할 수 있어야 합니다. Kotlin 테스트는 Kotest를 사용하고, 테스트명에도 `$writing-guide`의 한국어 작성 기준을 적용합니다.

## 테스트 구조

Kotest의 `FunSpec`을 사용합니다. 검증 대상을 `context`로 묶고, 각 계약을 `test`로 작성합니다.

```kotlin
class SampleTest :
    FunSpec({
        context("sampleOf가 값을 보존하는 계약") {
            test("Long 값을 전달하면 값과 KType을 보존한다") {
                val sample = sampleOf(1L)

                sample.value shouldBe 1L
                sample.type shouldBe typeOf<Long>()
            }
        }
    })
```

`context`에는 검증 대상과 계약을 씁니다. `test` 하나는 하나의 조건과 관찰 가능한 결과를 검증해야 합니다.

## 테스트명

테스트명은 `when-then` 또는 `if-then` 형식의 한국어 문장으로 작성합니다. 영어 키워드를 그대로 붙이지 않고 조건과 결과의 관계를 서술합니다.

### when-then 형식

행동이나 사건으로 결과가 발생할 때 `<행동 또는 상황>하면 <기대 결과>` 형식을 사용합니다.

- Do: `Long 값을 전달하면 값과 KType을 보존한다`
- Do: `여러 요소를 입력하면 선언 순서를 유지한다`
- Not: `Long 타입 테스트`
- Not: `선언 순서 검증`

### if-then 형식

상태나 논리 조건에 따라 결과가 성립할 때 `<조건>이면 <기대 결과>` 형식을 사용합니다.

- Do: `optional이 true이면 선택 상태를 유지한다`
- Do: `요청 본문이 빈 상태이면 body snippet을 생성하지 않는다`
- Not: `optional true 테스트`
- Not: `빈 요청 본문`

## 문장 작성 기준

- 테스트명만 읽어도 조건과 기대 결과를 알 수 있게 씁니다.
- 구현 과정보다 외부에서 관찰할 수 있는 동작을 씁니다.
- 하나의 테스트명에는 하나의 조건과 결과를 씁니다.
- 테스트명 안의 코드 식별자는 원문을 유지합니다.
- 서술은 `-한다`로 끝내고 불필요한 단어를 제거합니다.
