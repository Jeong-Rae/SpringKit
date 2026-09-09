# 테스트 작성 기준

테스트명에는 실행 조건과 예상 결과가 함께 드러나야 합니다. Kotlin 테스트는 Kotest로 작성합니다. 테스트명에도 `$writing-guide`의 한국어 작성 기준을 적용합니다.

## 테스트 구조

Kotest의 `FunSpec`을 사용합니다. 확인할 기능이나 상황은 `context`로 묶습니다. 조건과 예상 결과는 `test`에 작성합니다.

```kotlin
class SampleTest :
    FunSpec({
        context("sampleOf의 반환값") {
            test("Long 값을 입력하면, 값과 KType을 보존한다") {
                val sample = sampleOf(1L)

                sample.value shouldBe 1L
                sample.type shouldBe typeOf<Long>()
            }
        }
    })
```

`context`에는 확인할 기능이나 상황을 작성합니다. 각 `test`에서는 조건 하나와 그 결과 하나를 확인합니다.

## 테스트명

테스트명은 조건과 예상 결과를 자연스럽게 연결한 한국어 문장으로 작성합니다. 상황에 따라 `when-then` 또는 `if-then` 형식을 사용합니다.

### when-then 형식

입력이나 동작으로 결과가 발생하면 `<입력 또는 동작>하면, <예상 결과>`로 작성합니다.

- Do: `Long 값을 입력하면, 값과 KType을 보존한다`
- Do: `여러 요소를 입력하면, 선언 순서를 유지한다`
- Not: `Long 타입 테스트`
- Not: `선언 순서 검증`

### if-then 형식

특정 상태에서 결과가 달라지면 `<상태>이면, <예상 결과>`로 작성합니다.

- Do: `optional이 true이면, 선택 상태를 유지한다`
- Do: `요청 본문이 비어 있으면, 문서 조각을 생성하지 않는다`
- Not: `optional true 테스트`
- Not: `빈 요청 본문`

## 문장 작성 기준

- 테스트명만 읽어도 조건과 예상 결과를 알 수 있게 작성합니다.
- 테스트에서 직접 확인하는 결과를 작성합니다.
- 구현 순서나 내부 처리 방식은 테스트명에 작성하지 않습니다.
- 테스트명 하나에는 조건 하나와 예상 결과 하나만 작성합니다.
- 테스트명 안의 코드 식별자는 원문을 유지합니다.
- 뜻이 달라지지 않는 단어는 제거합니다.

## 경계값 검증

코드나 명세에서 동작이 달라지는 값을 정했다면 Kotest의 `withData`로 그 값을 모두 확인합니다. 각 값의 바로 앞뒤 값도 함께 확인합니다.

예: 정수 `x`가 `0`보다 커야 하면 `-1`, `0`, `1`을 확인합니다.

```kotlin
context("정수가 양수인지 확인할 때") {
    withData(
        nameFn = { (value, expected) ->
            val result = if (expected) "양수이다" else "양수가 아니다"
            "입력값이 $value이면, $result"
        },
        -1 to false,
        0 to false,
        1 to true,
    ) { (value, expected) ->
        isPositive(value) shouldBe expected
    }
}
```

같은 동작을 여러 타입이나 입력 조합으로 확인할 때도 `withData`를 사용합니다. 각 데이터의 테스트명은 상황에 맞는 `when-then` 또는 `if-then` 형식으로 작성합니다.
