# 테스트 작성 기준

테스트는 검증할 대상에 맞춰 범위를 나눕니다. 각 테스트는 하나의 행동과 예상 결과를 명확하게 확인합니다. 테스트 이름과 설명에는 `$writing-guide`를 적용합니다.

## 테스트 범위

### 도메인 단위 테스트

도메인 객체의 비즈니스 규칙을 단위 테스트로 검증합니다. 상태 변경, 계산, 허용 조건, 경계값을 확인합니다.

예:

```kotlin
scenario("승인 대기 중인 결제를 승인하면, 결제 상태가 승인이 됩니다.") {
    val payment = PaymentFixture.requested()

    payment.approve()

    payment.status shouldBe PaymentStatus.APPROVED
}
```

### API 계약 테스트

Presentation에서는 HTTP 요청과 응답이 선언된 API 계약에 맞는지 검증합니다. SpringKit의 선언적 REST Docs로 계약을 관리하고 문서화합니다. Application은 테스트 대역으로 연결합니다.

예:

```kotlin
class PaymentApiDocumentationTest : DeclarativeRestDocsTest() {
    @Test
    fun approvePayment() {
        documentation("approve-payment") {
            summary = "결제 승인"
            description = "승인 대기 중인 결제를 승인합니다."

            requestLine("post", "/api/payments/{paymentId}/approve") {
                pathVariable("paymentId", "결제 식별자", sample = 1L)
            }

            responseBody {
                field("status", "결제 상태", sample = "APPROVED")
            }
        }
    }
}
```

### 유스케이스 통합 테스트

Application에서 시작해 Domain, Infrastructure, 실제 DB까지 연결하여 검증합니다. 유스케이스의 결과와 트랜잭션, 데이터 저장을 대표적인 성공과 실패 사례로 확인합니다.

예:

```kotlin
scenario("승인 대기 중인 결제를 승인하면, 승인된 결제가 DB에 저장됩니다.") {
    val payment = paymentRepository.save(PaymentFixture.requested())

    paymentApprovalUseCase.approve(payment.id)

    val savedPayment = paymentRepository.findById(payment.id).orElseThrow()
    savedPayment.status shouldBe PaymentStatus.APPROVED
}
```

### 외부 시스템

우리 서비스가 소유하지 않는 외부 시스템은 Fake나 Stub으로 분리합니다. 우리 서비스가 사용하는 DB는 운영 환경과 같은 종류의 테스트 DB로 연결합니다.

예: PG사는 `FakePaymentGateway`로 연결하고, 결제 정보는 테스트 PostgreSQL에 저장합니다.

## Feature와 Scenario

### Feature

Kotest의 `FeatureSpec`을 사용합니다. `feature`에는 검증할 기능을 명사구로 작성하고, `scenario`에는 해당 기능의 행동 규칙을 작성합니다.

예:

```kotlin
class PaymentApprovalTest :
    FeatureSpec({
        feature("결제 승인") {
            scenario("승인 대기 중인 결제를 승인하면, 결제 상태가 승인이 됩니다.") {
                val payment = PaymentFixture.requested()

                payment.approve()

                payment.status shouldBe PaymentStatus.APPROVED
            }
        }
    })
```

### Scenario 이름

Scenario 이름에는 조건, 행동, 예상 결과를 하나의 완전한 한국어 문장으로 작성합니다. 테스트에서 직접 확인하는 결과를 쉽고 자연스럽게 표현합니다.

Scenario 문자열에도 `$writing-guide`를 적용하고, 코드 식별자와 도메인 용어를 일관되게 사용합니다.

예:

```text
승인 대기 중인 결제를 승인하면, 결제 상태가 승인이 됩니다.
승인된 결제를 다시 승인하면, 결제 승인 요청이 거부됩니다.
결제 금액이 최소 결제 금액 이상이면, 결제할 수 있습니다.
```

## SUT와 실행 단계

### SUT

각 Scenario는 하나의 SUT(System Under Test, 테스트 대상)를 중심으로 작성합니다. SUT는 검증할 행동을 수행하는 객체이며, 변수에는 도메인에 맞는 이름을 사용합니다.

예: 다음 Scenario의 SUT는 `payment`입니다.

```kotlin
scenario("승인된 결제를 취소하면, 결제가 취소됩니다.") {
    val payment = PaymentFixture.approved()

    payment.cancel()

    payment.status shouldBe PaymentStatus.CANCELLED
}
```

### Given, When, Then

Scenario 본문은 준비(Given), 실행(When), 검증(Then) 순서로 작성합니다. 세 부분은 빈 줄로 구분합니다.

Given에서는 행동에 필요한 초기 상태를 준비하고, 결과에 영향을 주는 값은 Fixture 호출부에 명시합니다. When에서는 SUT에 하나의 핵심 행동을 수행합니다. Then에서는 그 행동의 관찰 가능한 결과를 확인합니다.

예:

```kotlin
scenario("승인 대기 중인 결제를 승인하면, 결제가 승인됩니다.") {
    val payment = PaymentFixture.requested(amount = 10_000L)

    payment.approve()

    payment.status shouldBe PaymentStatus.APPROVED
    payment.approvedAt shouldNotBe null
}
```

### 오류 검증

행동이 거부되는 경우에는 호출자가 받는 예외를 검증합니다.

예:

```kotlin
scenario("승인된 결제를 다시 승인하면, 결제 승인 요청이 거부됩니다.") {
    val payment = PaymentFixture.approved()

    shouldThrow<PaymentAlreadyApprovedException> {
        payment.approve()
    }
}
```

## 경계값과 입력 조합

### 3-value BVA

입력값에 따라 동작이 달라지면 3-value BVA(Boundary Value Analysis, 경계값 분석)를 적용합니다. 각 경계에서 바로 아래 값, 경계값, 바로 위 값을 확인합니다.

예: 결제 가능 금액이 `100원 이상 1,000원 이하`인 경우

| 경계 | 확인할 값 |
| --- | --- |
| 최소 금액 | 99원, 100원, 101원 |
| 최대 금액 | 999원, 1,000원, 1,001원 |

### 데이터 기반 테스트

같은 행동 규칙을 여러 입력으로 검증할 때는 `withScenarios`를 사용합니다. 각 입력에 대한 Scenario 이름에도 조건과 예상 결과가 드러나야 합니다. 행동 규칙이 달라지면 별도의 Scenario로 작성합니다.

예:

```kotlin
feature("결제 가능 금액") {
    withScenarios(
        nameFn = { (amount, expected) ->
            val result =
                if (expected) "결제할 수 있습니다."
                else "결제할 수 없습니다."
            "결제 금액이 ${amount}원이면, $result"
        },
        99L to false,
        100L to true,
        101L to true,
        999L to true,
        1_000L to true,
        1_001L to false,
    ) { (amount, expected) ->
        val policy = PaymentPolicy(
            minimumAmount = 100L,
            maximumAmount = 1_000L,
        )

        val actual = policy.canPay(amount)

        actual shouldBe expected
    }
}
```
