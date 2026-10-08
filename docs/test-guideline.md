# 테스트 작성 기준

테스트명에는 실행 조건과 예상 결과가 함께 드러나야 합니다. Kotlin 테스트는 Kotest로 작성합니다. Feature와 Scenario 이름에도 `$writing-guide`의 한국어 작성 기준을 적용합니다.

## 테스트 구조

Kotest의 `FeatureSpec`을 사용합니다. 확인할 기능이나 도메인 행위는 `feature`로 묶고, 각 행동 규칙은 `scenario`로 작성합니다. Scenario 본문은 Given, When, Then 순서로 구성합니다.

```kotlin
class PaymentApprovalTest :
    FeatureSpec({
        feature("결제 승인") {
            scenario("승인 대기 중인 결제를 승인하면, 결제 상태가 승인으로 변경됩니다.") {
                // Given
                val payment = PaymentFixture.requested()

                // When
                payment.approve()

                // Then
                payment.status shouldBe PaymentStatus.APPROVED
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

Scenario 문자열도 일반 문장으로 보고 `$writing-guide`를 적용합니다. 도메인에서 관찰할 수 있는 결과를 쉽고 직접적인 말로 표현합니다.

예:

```text
승인 대기 중인 결제를 승인하면, 결제 상태가 승인으로 변경됩니다.
승인된 결제를 다시 승인하면, 결제 승인 요청이 거부됩니다.
결제 금액이 최소 결제 금액 이상이면, 결제할 수 있습니다.
결제 금액이 최소 결제 금액보다 작으면, 결제할 수 없습니다.
결제가 취소되면, 결제 금액이 환불됩니다.
```

Scenario 이름에는 다음 기준을 적용합니다.

- 조건과 예상 결과가 드러나게 작성합니다.
- 행동이 있는 경우 조건, 행동, 예상 결과가 모두 드러나게 작성합니다.
- 테스트에서 직접 확인하는 결과를 작성합니다.
- Scenario 하나에는 하나의 핵심 행동 규칙과 하나의 예상 결과를 작성합니다.
- 하나의 결과를 확인하기 위한 여러 assertion은 같은 Scenario에서 검증할 수 있습니다.
- 코드 식별자는 원문을 유지합니다.
- 도메인에서 같은 개념에는 같은 용어를 사용합니다.
- 조건절과 결과절의 관계가 바로 읽히도록 문장을 구성합니다.
- 뜻이 달라지지 않는 단어는 제거합니다.

## SUT

각 Scenario는 하나의 SUT(System Under Test, 테스트 대상)를 기준으로 작성합니다. SUT는 Scenario에서 행동을 수행하고 결과를 확인하는 중심 객체입니다.

Given에서 SUT와 행동에 필요한 상태를 준비합니다. 변수 이름에는 실제 도메인 이름을 사용하여 어떤 객체를 테스트하는지 바로 알 수 있게 작성합니다.

예:

```kotlin
// Given
val payment = PaymentFixture.requested()
```

위 Scenario의 SUT는 `payment`입니다. When에서는 `payment`에 행동을 수행하고, Then에서는 그 행동으로 달라진 `payment`의 상태나 반환 결과를 확인합니다.

여러 객체가 필요한 경우에도 Scenario에서 검증하는 중심 SUT를 하나로 정합니다.

```kotlin
// Given
val payment = PaymentFixture.requested(amount = 10_000L)
val account = AccountFixture.availableBalance(20_000L)

// When
payment.approve(account)

// Then
payment.status shouldBe PaymentStatus.APPROVED
```

## Given

Given은 행동을 수행하기 전에 이미 성립한 조건과 상태를 구성합니다.

Scenario 결과에 영향을 주는 초기 상태가 테스트 본문에 드러나게 작성합니다.

예:

```kotlin
// Given
val payment = PaymentFixture.requested()
```

Scenario에서 중요한 값은 호출부에 드러냅니다.

```kotlin
// Given
val payment = PaymentFixture.requested(amount = 10_000L)
```

## When

When은 SUT에 하나의 핵심 행동을 수행합니다. 하나의 Scenario에서는 하나의 behavior를 검증합니다.

예:

```kotlin
// When
payment.approve()
```

하나의 행동으로 여러 상태가 함께 바뀌는 경우에는 Then에서 같은 행동의 결과로 함께 검증할 수 있습니다.

```kotlin
// When
payment.approve()

// Then
payment.status shouldBe PaymentStatus.APPROVED
payment.approvedAt shouldNotBe null
```

## Then

Then은 When 이후 외부에서 관찰할 수 있는 결과를 검증합니다.

예:

```kotlin
// Then
payment.status shouldBe PaymentStatus.APPROVED
```

상태, 반환값, 오류처럼 호출자가 관찰할 수 있는 계약을 검증합니다.

## 경계값 검증

코드나 명세에서 입력값을 기준으로 동작이 달라지면 3-value BVA(Boundary Value Analysis)를 적용합니다.

각 경계값에서 바로 아래 값, 경계값, 바로 위 값을 확인합니다.

예: 최소 결제 금액이 `100원`이면 `99원`, `100원`, `101원`을 확인합니다.

같은 행동 규칙을 여러 값으로 검증할 때는 FeatureSpec의 `withScenarios`를 사용합니다. 각 데이터가 생성하는 Scenario 이름에도 `$writing-guide`를 적용하고 실제 결과를 직접 표현합니다.

```kotlin
feature("최소 결제 금액") {
    val policy = PaymentPolicy(minimumAmount = 100L)

    withScenarios(
        nameFn = { (amount, expected) ->
            val result = if (expected) "결제할 수 있습니다." else "결제할 수 없습니다."
            "결제 금액이 ${amount}원이면, $result"
        },
        99L to false,
        100L to true,
        101L to true,
    ) { (amount, expected) ->
        // When
        val actual = policy.canPay(amount)

        // Then
        actual shouldBe expected
    }
}
```

유효한 범위에 최솟값과 최댓값이 모두 있으면 각 경계에 3-value BVA를 적용합니다.

예: 결제 금액의 유효 범위가 `100원 이상 1,000원 이하`이면 `99원`, `100원`, `101원`, `999원`, `1,000원`, `1,001원`을 확인합니다.

```kotlin
feature("결제 금액 범위") {
    val policy = PaymentPolicy(
        minimumAmount = 100L,
        maximumAmount = 1_000L,
    )

    withScenarios(
        nameFn = { (amount, expected) ->
            val result = if (expected) "결제할 수 있습니다." else "결제할 수 없습니다."
            "결제 금액이 ${amount}원이면, $result"
        },
        99L to false,
        100L to true,
        101L to true,
        999L to true,
        1_000L to true,
        1_001L to false,
    ) { (amount, expected) ->
        // When
        val actual = policy.canPay(amount)

        // Then
        actual shouldBe expected
    }
}
```

같은 행동 규칙을 여러 타입이나 입력 조합으로 확인할 때도 `withScenarios`를 사용합니다. 입력에 따라 검증하는 행동 규칙이 달라지면 별도의 Scenario로 작성합니다.
