# 실패 조건과 제약을 먼저 처리하기

계속 진행할 수 없는 조건을 먼저 처리하여 정상적인 실행 흐름이 한 방향으로 이어지도록 구성합니다.

## 작성 지침

검증과 제약 조건은 실제 작업보다 먼저 확인합니다. 여러 조건이 있다면 더 적은 비용으로 판단할 수 있는 조건을 먼저 확인하고, 비슷한 비용이라면 더 넓은 범위에 적용되는 조건을 먼저 확인합니다.

현재 작업을 중단하거나 다른 결과를 반환할 수 있는 조건은 확인한 위치에서 처리합니다. 예외와 조기 반환 흐름을 먼저 제거한 뒤 정상적인 동작을 이어갑니다.

## Do Not 예시

```kotlin
fun placeOrder(command: PlaceOrderCommand) {
    val account = accountRepository.require(command.accountId) // Do Not: 기본 입력 검증보다 조회를 먼저 수행
    val quote = quoteClient.current(command.symbol) // Do Not: 앞선 제약을 확인하기 전에 외부 호출 수행

    if (command.quantity.isPositive()) {
        if (market.isOpen()) {
            if (account.canTrade(command.symbol)) {
                if (account.canBuy(quote.price, command.quantity)) {
                    broker.submit(command) // Do Not: 정상 동작이 실패 조건 안에 중첩
                } else {
                    throw InsufficientBalanceException()
                }
            } else {
                throw TradingRestrictedException()
            }
        } else {
            throw MarketClosedException()
        }
    } else {
        throw InvalidOrderQuantityException()
    }
}

private fun tradableQuantity(
    account: Account,
    security: Security,
): Quantity {
    if (account.isActive()) {
        if (security.isTradable()) {
            if (!account.isRestricted(security)) {
                return account.buyingPower().quantityFor(security) // Do Not: 정상 계산이 조건 안에 중첩
            }
        }
    }

    return Quantity.ZERO
}
```

## Do 예시

```kotlin
fun placeOrder(command: PlaceOrderCommand) {
    require(command.quantity.isPositive()) // Do: 가벼운 입력 제약을 먼저 확인

    if (!market.isOpen()) {
        throw MarketClosedException() // Do: 모든 주문에 적용되는 넓은 제약을 먼저 처리
    }

    val account = accountRepository.require(command.accountId) // Do: 선행 제약을 통과한 뒤 조회

    if (!account.canTrade(command.symbol)) {
        throw TradingRestrictedException() // Do: 계좌에 한정된 제약을 처리
    }

    val quote = quoteClient.current(command.symbol) // Do: 필요한 경우에만 비용이 큰 외부 호출 수행

    if (!account.canBuy(quote.price, command.quantity)) {
        throw InsufficientBalanceException() // Do: 조회 결과가 필요한 좁은 제약을 처리
    }

    broker.submit(command) // Do: 실패 흐름을 처리한 뒤 정상 동작 수행
}

private fun tradableQuantity(
    account: Account,
    security: Security,
): Quantity {
    if (!account.isActive()) {
        return Quantity.ZERO // Do: 넓은 범위의 제외 조건을 먼저 처리
    }

    if (!security.isTradable()) {
        return Quantity.ZERO // Do: 계산할 필요가 없는 조건을 먼저 처리
    }

    if (account.isRestricted(security)) {
        return Quantity.ZERO // Do: 더 좁은 제약을 확인한 위치에서 결과 반환
    }

    return account.buyingPower().quantityFor(security) // Do: 제외 조건 처리 후 정상 결과 계산
}
```
