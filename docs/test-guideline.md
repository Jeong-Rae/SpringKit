# 테스트 작성 기준

테스트명에는 실행 조건과 예상 결과가 함께 드러나야 합니다. Kotlin 테스트는 Kotest로 작성합니다. 테스트명에도 `$writing-guide`의 한국어 작성 기준을 적용합니다.

## 테스트 구조

Kotest의 `FeatureSpec`을 사용합니다. 확인할 기능이나 도메인 행위는 `feature`로 묶고, 각 행동 규칙은 `scenario`로 작성합니다. Scenario 본문은 Given, When, Then 순서로 구성합니다.

```kotlin
class SampleTest :
    FeatureSpec({
        feature("sampleOf 반환값") {
            scenario("Long 값을 입력하면, 값과 KType을 보존합니다.") {
                // Given
                val value = 1L

                // When
                val sample = sampleOf(value)

                // Then
                sample.value shouldBe value
                sample.type shouldBe typeOf<Long>()
            }
        }
    })
```

Feature 이름은 기능을 식별할 수 있는 명사구로 작성합니다. Scenario는 조건, 행동, 예상 결과를 하나의 완전한 한국어 문장으로 작성합니다.

## Scenario 이름

Scenario 이름만 읽어도 어떤 조건에서 어떤 행동을 했을 때 어떤 결과가 발생하는지 알 수 있어야 합니다.

기본 문장 구조는 다음과 같습니다.

```text
[조건]에서 [행동]하면, [결과]가 발생합니다.
```

한국어 문맥에 맞게 조사와 서술어를 자연스럽게 조정합니다.

예:

```text
Long 값을 입력하면, 값과 KType을 보존합니다.
결제된 주문에서 취소를 요청하면, 주문 취소 요청이 거부됩니다.
유효한 쿠폰을 주문에 적용하면, 주문 금액이 할인됩니다.
optional이 true이면, 선택 상태를 유지합니다.
요청 본문이 비어 있으면, 문서 조각을 생성하지 않습니다.
```

Scenario 이름에는 다음 기준을 적용합니다.

- 조건과 예상 결과가 드러나게 작성합니다.
- 행동이 있는 경우 조건, 행동, 예상 결과가 모두 드러나게 작성합니다.
- 테스트에서 직접 확인하는 결과를 작성합니다.
- Scenario 하나에는 하나의 핵심 행동 규칙과 하나의 예상 결과를 작성합니다.
- 하나의 결과를 확인하기 위한 여러 assertion은 같은 Scenario에서 검증할 수 있습니다.
- 코드 식별자는 원문을 유지합니다.
- 도메인에서 같은 개념에는 같은 용어를 사용합니다.
- 서술형 문장은 `-니다.`로 끝냅니다.
- 조건절과 결과절을 구분할 때 `-면,` 형식을 사용합니다.
- 뜻이 달라지지 않는 단어는 제거합니다.

## Given

Given은 행동을 수행하기 전에 이미 성립한 조건과 상태를 구성합니다.

Scenario 결과에 영향을 주는 초기 상태가 테스트 본문에 드러나게 작성합니다.

예:

```kotlin
// Given
val value = 1L
```

도메인 테스트에서는 Scenario의 조건을 표현하는 객체 상태를 Given에서 구성합니다.

```kotlin
// Given
val order = paidOrder()
```

## When

When은 Scenario를 발생시키는 핵심 행동을 수행합니다.

예:

```kotlin
// When
val sample = sampleOf(value)
```

도메인 테스트에서는 객체가 제공하는 행동을 통해 상태를 변경합니다.

```kotlin
// When
order.cancel()
```

## Then

Then은 When 이후 외부에서 관찰할 수 있는 결과를 검증합니다.

예:

```kotlin
// Then
sample.value shouldBe value
sample.type shouldBe typeOf<Long>()
```

도메인 테스트에서는 상태, 반환값, 오류처럼 호출자가 관찰할 수 있는 계약을 검증합니다.

```kotlin
// Then
order.status shouldBe OrderStatus.CANCELLED
```

## 경계값 검증

코드나 명세에서 동작이 달라지는 경계가 있다면 해당 경계값과 바로 앞뒤 값을 모두 확인합니다.

예: 정수 `x`가 `0`보다 커야 하면 `-1`, `0`, `1`을 확인합니다.

같은 행동 규칙을 여러 값으로 검증할 때는 FeatureSpec의 `withScenarios`를 사용합니다. 각 데이터가 생성하는 Scenario 이름에도 동일한 문장 작성 기준을 적용합니다.

```kotlin
feature("정수의 양수 판별") {
    withScenarios(
        nameFn = { (value, expected) ->
            val result = if (expected) "양수로 판별합니다." else "양수가 아닌 값으로 판별합니다."
            "입력값이 $value이면, $result"
        },
        -1 to false,
        0 to false,
        1 to true,
    ) { (value, expected) ->
        // When
        val actual = isPositive(value)

        // Then
        actual shouldBe expected
    }
}
```

같은 행동 규칙을 여러 타입이나 입력 조합으로 확인할 때도 `withScenarios`를 사용합니다. 입력에 따라 검증하는 행동 규칙 자체가 달라지면 별도의 Scenario로 작성합니다.
