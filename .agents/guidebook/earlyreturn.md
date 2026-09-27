Early Return과 Fail Fast를 사용합니다

조건을 만족하지 않는 경우에는 가능한 한 빨리 함수를 종료합니다. 유효하지 않은 입력이나 상태는 발견한 시점에 즉시 실패시킵니다.

정상적인 실행 경로를 가장 바깥쪽에 유지하여 핵심 동작을 쉽게 파악할 수 있도록 합니다.

Not:

fun hit(player: Player) {
    if (game.isPlayerTurn(player)) {
        if (!player.isBust()) {
            if (deck.hasCards()) {
                player.receive(deck.draw())
            }
        }
    }
}

Do:

fun hit(player: Player) {
    if (!game.isPlayerTurn(player)) {
        return
    }

    if (player.isBust()) {
        return
    }

    if (deck.isEmpty()) {
        throw EmptyDeckException()
    }

    player.receive(deck.draw())
}

유효하지 않은 입력도 가능한 한 진입 지점에서 검증합니다.

fun placeBet(
    player: Player,
    bet: Bet,
) {
    require(bet.isPositive()) {
        "베팅 금액은 0보다 커야 합니다."
    }

    if (!player.canBet(bet)) {
        throw BetRejectedException()
    }

    player.placeBet(bet)
}