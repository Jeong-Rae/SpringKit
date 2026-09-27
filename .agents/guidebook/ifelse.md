조건문의 중첩을 제한합니다

중첩된 조건문보다 Early Return과 함수 분리를 사용합니다.

조건 구문의 중첩 깊이는 최대 2단계로 제한합니다. 2단계를 넘어가면 분기마다 수행하는 작업을 별도 함수로 분리하거나 제어 흐름을 다시 설계합니다.

Not:

```kotlin
fun advanceTurn(player: Player) {
    if (game.isPlayerTurn(player)) {
        if (player.hasFinished()) {
            if (game.hasNextPlayer()) {
                val nextPlayer = game.nextPlayer()
                nextPlayer.startTurn()
                notifyTurnStarted(nextPlayer)
            } else {
                dealer.play()
                settleRound()
            }
        }
    }
}
```

Do:

```kotlin
fun advanceTurn(player: Player) {
    if (!game.isPlayerTurn(player) || !player.hasFinished()) {
        return
    }

    if (game.hasNextPlayer()) {
        startNextPlayerTurn()
        return
    }

    finishRound()
}

private fun startNextPlayerTurn() {
    val nextPlayer = game.nextPlayer()
    nextPlayer.startTurn()
    notifyTurnStarted(nextPlayer)
}

private fun finishRound() {
    dealer.play()
    settleRound()
}
```

다음 플레이어의 차례를 시작하는 절차와 라운드를 끝내는 절차는 각각 별도 함수가 담당합니다. `advanceTurn`은 어느 절차를 실행할지만 결정합니다.

else 사용을 지양합니다

조건을 처리한 뒤 실행을 종료할 수 있다면 else를 사용하지 않습니다. 정상 흐름을 한 단계의 들여쓰기에서 읽을 수 있도록 구성합니다.

Not:

```kotlin
fun play(player: Player) {
    if (player.isBust()) {
        game.finishTurn(player)
        notifyTurnFinished(player)
    } else {
        requestAction(player)
    }
}
```

Do:

```kotlin
fun play(player: Player) {
    if (player.isBust()) {
        finishBustTurn(player)
        return
    }

    requestAction(player)
}

private fun finishBustTurn(player: Player) {
    game.finishTurn(player)
    notifyTurnFinished(player)
}
```

else 자체를 금지하는 것이 목적은 아닙니다. Early Return을 적용했을 때 정상 흐름이 더 명확해지는 경우에 else보다 Early Return을 우선합니다.
