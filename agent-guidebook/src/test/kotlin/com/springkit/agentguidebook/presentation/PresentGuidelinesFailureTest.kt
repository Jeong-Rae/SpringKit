package com.springkit.agentguidebook.presentation

import com.springkit.agentguidebook.domain.Guideline
import com.springkit.agentguidebook.domain.GuidelineEvent
import com.springkit.agentguidebook.domain.GuidelineId
import com.springkit.agentguidebook.domain.Language
import com.springkit.agentguidebook.domain.MatchCondition
import com.springkit.agentguidebook.domain.RepositoryIdentity
import com.springkit.agentguidebook.domain.Scope
import com.springkit.agentguidebook.domain.SourceFile
import com.springkit.agentguidebook.search.InputReader
import com.springkit.agentguidebook.search.SearchGuidelinesUseCase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PresentGuidelinesFailureTest :
    FeatureSpec({
      feature("Presentation 이력 조회 실패") {
        scenario("기존 이력 조회에서 저장소 오류가 나면, 원래 예외를 전달하고 이력을 보존합니다.") {
          val failure = IllegalArgumentException("Presentation 이력을 읽을 수 없습니다.")
          val initialHistory = listOf(presentation("session-a", "older-rule"))
          val persistence = memoryPresentationPersistence(initialHistory, failure)
          val fixture = fixture(persistence)
          val before = presentationValues(fixture.repository.allPresentations())

          val thrown =
              shouldThrow<IllegalArgumentException> {
                fixture.service.present(request("session-a"))
              }

          thrown shouldBe failure
          presentationValues(fixture.repository.allPresentations()) shouldBe before
        }
      }
    })

private data class PresentationServiceFixture(
    val service: PresentGuidelinesUseCase,
    val repository: FailureRepository,
)

private data class MemoryPresentationPersistence(
    val unitOfWork: FailurePresentationUnitOfWork,
    val repository: FailureRepository,
    val store: FailurePresentationStore,
)

private class FailureInputReader(
    private val requireSearchOutsideUnitOfWork: () -> Unit,
) : InputReader {
  override fun validateWorkingDirectory(cwd: Path) {
    requireSearchOutsideUnitOfWork()
  }

  override fun readKotlinSources(filepaths: Collection<Path>): List<SourceFile> {
    requireSearchOutsideUnitOfWork()
    return listOf(SOURCE).filter { source -> source.filepath in filepaths }
  }

  override fun readGuidelines(root: Path): List<Guideline> {
    requireSearchOutsideUnitOfWork()
    return GUIDELINES
  }

  override fun readRepositoryIdentity(cwd: Path): RepositoryIdentity {
    requireSearchOutsideUnitOfWork()
    return RepositoryIdentity("acme", "backend-order")
  }
}

private class FailurePresentationStore(
    initialHistory: Collection<Presentation>,
    private val readFailure: RuntimeException,
) {
  private val committed = ArrayList(initialHistory)
  private var pending: MutableList<Presentation>? = null

  fun begin() {
    check(pending == null) { "Fake UnitOfWork transaction은 중첩할 수 없습니다." }
    pending = ArrayList(committed)
  }

  fun commit() {
    val transaction = activePresentations()
    committed.clear()
    committed.addAll(transaction)
    pending = null
  }

  fun rollback() {
    pending = null
  }

  fun requireInactive() {
    check(pending == null) { "Guideline 검색은 UnitOfWork transaction 밖에서 수행해야 합니다." }
  }

  fun activePresentations(): MutableList<Presentation> =
      checkNotNull(pending) { "Fake Repository는 활성 UnitOfWork 안에서만 사용할 수 있습니다." }

  fun allPresentations(): List<Presentation> = committed.toList()

  fun readFailure(): RuntimeException = readFailure
}

private class FailurePresentationUnitOfWork(
    private val store: FailurePresentationStore,
) : UnitOfWork {
  override fun <T> execute(operation: () -> T): T {
    store.begin()

    return try {
      val result = operation()
      store.commit()
      result
    } catch (failure: Throwable) {
      store.rollback()
      throw failure
    }
  }
}

private class FailureRepository(
    private val store: FailurePresentationStore,
) : Repository {
  override fun presentations(sessionId: SessionId): List<Presentation> {
    throw store.readFailure()
  }

  override fun record(presentations: Collection<Presentation>) {
    store.activePresentations().addAll(presentations)
  }

  fun allPresentations(): List<Presentation> = store.allPresentations()
}

private fun fixture(persistence: MemoryPresentationPersistence): PresentationServiceFixture {
  val reader = FailureInputReader(persistence.store::requireInactive)
  val service =
      PresentGuidelinesUseCase(
          guidelineSearchService = SearchGuidelinesUseCase(reader),
          unitOfWork = persistence.unitOfWork,
          presentationRepository = persistence.repository,
          clock = FIXED_CLOCK,
      )

  return PresentationServiceFixture(service, persistence.repository)
}

private fun memoryPresentationPersistence(
    initialHistory: Collection<Presentation>,
    readFailure: RuntimeException,
): MemoryPresentationPersistence {
  val store = FailurePresentationStore(initialHistory, readFailure)

  return MemoryPresentationPersistence(
      unitOfWork = FailurePresentationUnitOfWork(store),
      repository = FailureRepository(store),
      store = store,
  )
}

private fun request(sessionId: String): PresentationRequest =
    PresentationRequest(
        sessionId = SessionId(sessionId),
        cwd = Path.of("/virtual/project"),
        filepaths = listOf(SOURCE.filepath),
    )

private fun presentation(
    sessionId: String,
    guidelineId: String,
): Presentation =
    Presentation(
        sessionId = SessionId(sessionId),
        guidelineId = GuidelineId(guidelineId),
        presentedAt = PRESENTED_AT,
    )

private fun presentationValues(
    presentations: Collection<Presentation>,
): List<Triple<SessionId, GuidelineId, Instant>> = presentations.map { presentation ->
  Triple(presentation.sessionId, presentation.guidelineId, presentation.presentedAt)
}

private val PRESENTED_AT: Instant = Instant.parse("2026-10-18T12:00:00Z")
private val FIXED_CLOCK: Clock = Clock.fixed(PRESENTED_AT, ZoneOffset.UTC)
private val SOURCE =
    SourceFile(
        filepath = Path.of("/virtual/project/src/OrderService.kt"),
        content = "class OrderService",
        language = Language("kotlin"),
    )
private val GUIDELINES =
    listOf(
        Guideline(
            id = GuidelineId("first-rule"),
            scope = Scope(repositories = listOf("*"), languages = listOf("kotlin")),
            events = listOf(GuidelineEvent("file_changed")),
            match = MatchCondition(listOf("OrderService")),
            content = "서비스 규칙은 첫 번째 순서를 유지합니다.",
        ),
        Guideline(
            id = GuidelineId("second-rule"),
            scope = Scope(repositories = listOf("*"), languages = listOf("kotlin")),
            events = listOf(GuidelineEvent("file_changed")),
            match = MatchCondition(listOf("OrderService")),
            content = "저장 규칙은 두 번째 순서를 유지합니다.",
        ),
    )
