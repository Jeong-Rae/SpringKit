null을 반환하지 않습니다

함수의 반환값으로 null을 사용하지 않습니다.

null은 호출자에게 의미를 제공하지 않으면서 모든 호출 지점에 별도의 null 검사를 요구합니다.

Not:

fun findGame(id: GameId): Game?

대상이 반드시 존재해야 하는 함수라면 존재하지 않는 상태를 예외로 표현합니다.

fun requireGame(id: GameId): Game =
    gameRepository.find(id)
        .orElseThrow {
            GameNotFoundException(id)
        }

대상이 존재하지 않는 상황이 정상적인 도메인 상태라면 해당 의미를 타입으로 표현합니다.

sealed interface GameLookup {

    data class Found(
        val game: Game,
    ) : GameLookup

    data object NotFound : GameLookup
}

fun findGame(id: GameId): GameLookup =
    gameRepository.find(id)

컬렉션 조회에서는 결과가 없다는 의미로 null을 반환하지 않고 빈 컬렉션을 반환합니다.

Not:

fun findPlayers(tableId: TableId): List<Player>?

Do:

fun findPlayers(tableId: TableId): List<Player> =
    playerRepository.findAll(tableId)

조회 결과가 없다면 다음과 같이 빈 컬렉션을 반환합니다.

emptyList()

Java API나 외부 시스템에서 null이 들어올 수 있다면 시스템 경계에서 즉시 의미 있는 타입이나 예외로 변환합니다. 내부 도메인까지 null을 전파하지 않습니다.