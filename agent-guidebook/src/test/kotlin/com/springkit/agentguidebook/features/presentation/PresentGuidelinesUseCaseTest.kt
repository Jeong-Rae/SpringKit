package com.springkit.agentguidebook.features.presentation

import com.springkit.agentguidebook.core.Guideline
import com.springkit.agentguidebook.core.MatchCondition
import com.springkit.agentguidebook.core.MatchedGuideline
import com.springkit.agentguidebook.core.Presentation
import com.springkit.agentguidebook.core.Scope
import com.springkit.agentguidebook.errors.GuidelineLoadException
import com.springkit.agentguidebook.errors.InvalidSearchRequestException
import com.springkit.agentguidebook.errors.RetryablePresentationStorageException
import com.springkit.agentguidebook.models.GuidelineEvent
import com.springkit.agentguidebook.models.GuidelineId
import com.springkit.agentguidebook.models.GuidelinePresentationRequest
import com.springkit.agentguidebook.models.Language
import com.springkit.agentguidebook.models.RepositoryIdentity
import com.springkit.agentguidebook.models.SessionId
import com.springkit.agentguidebook.models.SourceFile
import com.springkit.agentguidebook.services.GuidelineSearchInputReader
import com.springkit.agentguidebook.services.GuidelineSearchService
import com.springkit.agentguidebook.services.PresentationRepository
import com.springkit.agentguidebook.services.UnitOfWork
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PresentGuidelinesUseCaseTest :
    FeatureSpec({
      feature("Session별 지침 제공") {
        scenario("처음 요청하면 검색 순서대로 지침을 반환하고 같은 시각으로 기록합니다.") {
          val fixture = fixture()

          val result = fixture.useCase.execute(request("session-a"))

          result shouldContainExactly fixture.matches
          presentationValues(
              fixture.repository.presentationsFor(SessionId("session-a"))
          ) shouldContainExactly fixture.matches.map { presentationValue("session-a", it.id.value) }
        }

        scenario("같은 Session에서 반복 요청하면 이미 제공한 지침을 다시 반환하지 않습니다.") {
          val fixture = fixture()
          val request = request("session-a")

          fixture.useCase.execute(request)
          val repeated = fixture.useCase.execute(request)

          repeated shouldBe emptyList()
          presentationValues(
              fixture.repository.presentationsFor(SessionId("session-a"))
          ) shouldContainExactly fixture.matches.map { presentationValue("session-a", it.id.value) }
        }

        scenario("기존 일부 이력만 제외하고 나머지를 검색 순서대로 기록합니다.") {
          val earlierTime = Instant.parse("2026-10-17T12:00:00Z")
          val alreadyPresented = presentation("session-a", "second-rule", earlierTime)
          val fixture = fixture(initialHistory = listOf(alreadyPresented))

          val result = fixture.useCase.execute(request("session-a"))

          result shouldContainExactly listOf(fixture.matches.first())
          presentationValues(
              fixture.repository.presentationsFor(SessionId("session-a"))
          ) shouldContainExactly
              listOf(
                  presentationValue("session-a", "second-rule", earlierTime),
                  presentationValue("session-a", "first-rule"),
              )
        }

        scenario("다른 Session에는 같은 지침을 새로 제공하고 이력을 분리합니다.") {
          val fixture = fixture()

          val firstSession = fixture.useCase.execute(request("session-a"))
          val secondSession = fixture.useCase.execute(request("session-b"))

          firstSession shouldContainExactly fixture.matches
          secondSession shouldContainExactly fixture.matches
          presentationValues(
              fixture.repository.presentationsFor(SessionId("session-a"))
          ) shouldContainExactly fixture.matches.map { presentationValue("session-a", it.id.value) }
          presentationValues(
              fixture.repository.presentationsFor(SessionId("session-b"))
          ) shouldContainExactly fixture.matches.map { presentationValue("session-b", it.id.value) }
        }

        scenario("이미 제공한 ID의 본문이 바뀌어도 해당 지침을 다시 반환하지 않습니다.") {
          val fixture = fixture()
          fixture.useCase.execute(request("session-a"))
          fixture.reader.guidelines =
              fixture.reader.guidelines.map { guideline ->
                if (guideline.id.value == "first-rule") {
                  guidelineWithContent(guideline, "수정된 한글 지침 본문입니다.")
                } else {
                  guideline
                }
              }

          val result = fixture.useCase.execute(request("session-a"))

          result shouldBe emptyList()
          presentationValues(
              fixture.repository.presentationsFor(SessionId("session-a"))
          ) shouldContainExactly fixture.matches.map { presentationValue("session-a", it.id.value) }
        }
      }

      feature("검색과 저장 경계의 실패") {
        scenario("검색 결과가 비어 있으면 UnitOfWork와 저장소에 접근하지 않습니다.") {
          val initialHistory = listOf(presentation("session-a", "older-rule"))
          val fixture =
              fixture(
                  initialHistory = initialHistory,
                  sources = emptyList(),
                  unitOfWork = UnexpectedPresentationUnitOfWork,
                  presentationRepository = UnexpectedPresentationRepository,
              )

          val result = fixture.useCase.execute(request("session-a"))

          result shouldBe emptyList()
          presentationValues(fixture.store.allPresentations()) shouldContainExactly
              presentationValues(initialHistory)
        }

        scenario("검색 오류는 원래 예외 객체와 기존 이력을 보존합니다.") {
          val failure = GuidelineLoadException("지침 파일을 읽을 수 없습니다.")
          val initialHistory = listOf(presentation("session-a", "older-rule"))
          val fixture =
              fixture(
                  initialHistory = initialHistory,
                  searchFailure = failure,
                  unitOfWork = UnexpectedPresentationUnitOfWork,
                  presentationRepository = UnexpectedPresentationRepository,
              )
          val before = presentationValues(fixture.store.allPresentations())

          val thrown =
              shouldThrow<GuidelineLoadException> {
                fixture.useCase.execute(request("session-a"))
              }

          (thrown === failure) shouldBe true
          presentationValues(fixture.store.allPresentations()) shouldBe before
        }

        scenario("두 번째 기록이 실패하면 앞선 기록도 rollback하고 원래 예외를 전달합니다.") {
          val failure = RetryablePresentationStorageException("두 번째 기록 실패")
          val initialHistory = listOf(presentation("session-a", "older-rule"))
          val fixture =
              fixture(
                  initialHistory = initialHistory,
                  failOnGuidelineId = GuidelineId("second-rule"),
                  saveFailure = failure,
              )
          val before = presentationValues(fixture.store.allPresentations())

          val thrown =
              shouldThrow<RetryablePresentationStorageException> {
                fixture.useCase.execute(request("session-a"))
              }

          (thrown === failure) shouldBe true
          presentationValues(fixture.store.allPresentations()) shouldBe before
        }

        scenario("commit이 실패하면 이력을 rollback하고 성공 결과를 반환하지 않습니다.") {
          val failure = RetryablePresentationStorageException("commit 실패")
          val initialHistory = listOf(presentation("session-a", "older-rule"))
          val fixture =
              fixture(
                  initialHistory = initialHistory,
                  commitFailure = failure,
              )
          val before = presentationValues(fixture.store.allPresentations())

          val thrown =
              shouldThrow<RetryablePresentationStorageException> {
                fixture.useCase.execute(request("session-a"))
              }

          (thrown === failure) shouldBe true
          presentationValues(fixture.store.allPresentations()) shouldBe before
        }

        scenario("검색은 UnitOfWork 밖에서 실행하고 이력 조회와 기록은 안에서 실행합니다.") {
          val fixture = fixture(requireSearchOutsideUnitOfWork = true)

          val result = fixture.useCase.execute(request("session-a"))

          result shouldContainExactly fixture.matches
          presentationValues(
              fixture.repository.presentationsFor(SessionId("session-a"))
          ) shouldContainExactly fixture.matches.map { presentationValue("session-a", it.id.value) }
        }

        scenario("상대 cwd로 요청하면 검색 예외를 내고 이력을 보존합니다.") {
          val initialHistory = listOf(presentation("session-a", "older-rule"))
          val fixture =
              fixture(
                  initialHistory = initialHistory,
                  unitOfWork = UnexpectedPresentationUnitOfWork,
                  presentationRepository = UnexpectedPresentationRepository,
              )
          val before = presentationValues(fixture.store.allPresentations())

          shouldThrow<InvalidSearchRequestException> {
            fixture.useCase.execute(request("session-a", cwd = Path.of("project")))
          }

          presentationValues(fixture.store.allPresentations()) shouldBe before
        }

        scenario("상대 filepath로 요청하면 검색 예외를 내고 이력을 보존합니다.") {
          val initialHistory = listOf(presentation("session-a", "older-rule"))
          val fixture =
              fixture(
                  initialHistory = initialHistory,
                  unitOfWork = UnexpectedPresentationUnitOfWork,
                  presentationRepository = UnexpectedPresentationRepository,
              )
          val before = presentationValues(fixture.store.allPresentations())

          shouldThrow<InvalidSearchRequestException> {
            fixture.useCase.execute(
                request("session-a", filepaths = listOf(Path.of("src/OrderService.kt")))
            )
          }

          presentationValues(fixture.store.allPresentations()) shouldBe before
        }
      }
    })

private data class PresentationFixture(
    val useCase: PresentGuidelinesUseCase,
    val repository: MemoryPresentationRepository,
    val reader: FakeGuidelineSearchInputReader,
    val store: MemoryPresentationStore,
    val matches: List<MatchedGuideline>,
)

private class FakeGuidelineSearchInputReader(
    var guidelines: List<Guideline> = GUIDELINES,
    private val sources: List<SourceFile> = listOf(SOURCE),
    private val searchFailure: RuntimeException? = null,
    private val requireOutsideUnitOfWork: () -> Unit = {},
) : GuidelineSearchInputReader {
  override fun validateWorkingDirectory(cwd: Path) {
    requireOutsideUnitOfWork()
  }

  override fun readKotlinSources(filepaths: Collection<Path>): List<SourceFile> {
    requireOutsideUnitOfWork()
    return sources.filter { source -> source.filepath in filepaths }
  }

  override fun readGuidelines(root: Path): List<Guideline> {
    requireOutsideUnitOfWork()
    searchFailure?.let { throw it }
    return guidelines
  }

  override fun readRepositoryIdentity(cwd: Path): RepositoryIdentity {
    requireOutsideUnitOfWork()
    return RepositoryIdentity("acme", "backend-order")
  }
}

private class MemoryPresentationStore(
    initialHistory: Collection<Presentation>,
    private val commitFailure: RuntimeException? = null,
) {
  private val committed = ArrayList(initialHistory)
  private var pending: MutableList<Presentation>? = null

  fun begin() {
    check(pending == null) { "Fake UnitOfWork transaction은 중첩할 수 없습니다." }
    pending = ArrayList(committed)
  }

  fun commit() {
    commitFailure?.let { throw it }
    val transaction = activePresentations()
    committed.clear()
    committed.addAll(transaction)
    pending = null
  }

  fun rollback() {
    pending = null
  }

  fun activePresentations(): MutableList<Presentation> =
      checkNotNull(pending) { "Fake Repository는 활성 UnitOfWork 안에서만 사용할 수 있습니다." }

  fun requireInactive() {
    check(pending == null) { "Guideline 검색은 UnitOfWork transaction 밖에서 수행해야 합니다." }
  }

  fun presentationsFor(sessionId: SessionId): List<Presentation> =
      committed.filter { presentation ->
        presentation.sessionId == sessionId
      }

  fun allPresentations(): List<Presentation> = committed.toList()
}

private class FakePresentationUnitOfWork(
    private val store: MemoryPresentationStore,
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

private class MemoryPresentationRepository(
    private val store: MemoryPresentationStore,
    private val failOnGuidelineId: GuidelineId? = null,
    private val saveFailure: RuntimeException? = null,
) : PresentationRepository {
  override fun presentations(sessionId: SessionId): List<Presentation> =
      store.activePresentations().filter { presentation -> presentation.sessionId == sessionId }

  override fun record(presentations: Collection<Presentation>) {
    presentations.forEach { presentation ->
      if (presentation.guidelineId == failOnGuidelineId) {
        throw checkNotNull(saveFailure)
      }
      store.activePresentations().add(presentation)
    }
  }

  fun presentationsFor(sessionId: SessionId): List<Presentation> = store.presentationsFor(sessionId)

  fun allPresentations(): List<Presentation> = store.allPresentations()
}

private object UnexpectedPresentationRepository : PresentationRepository {
  override fun presentations(sessionId: SessionId): List<Presentation> =
      error("UnitOfWork에 진입하지 않은 요청은 Presentation 이력을 조회할 수 없습니다.")

  override fun record(presentations: Collection<Presentation>) {
    error("UnitOfWork에 진입하지 않은 요청은 Presentation 이력을 기록할 수 없습니다.")
  }
}

private object UnexpectedPresentationUnitOfWork : UnitOfWork {
  override fun <T> execute(operation: () -> T): T = error("검색 전처리에서 끝난 요청은 UnitOfWork를 실행할 수 없습니다.")
}

private fun fixture(
    initialHistory: Collection<Presentation> = emptyList(),
    sources: List<SourceFile> = listOf(SOURCE),
    searchFailure: RuntimeException? = null,
    failOnGuidelineId: GuidelineId? = null,
    saveFailure: RuntimeException? = null,
    commitFailure: RuntimeException? = null,
    requireSearchOutsideUnitOfWork: Boolean = false,
    unitOfWork: UnitOfWork? = null,
    presentationRepository: PresentationRepository? = null,
): PresentationFixture {
  val store = MemoryPresentationStore(initialHistory, commitFailure)
  val reader =
      FakeGuidelineSearchInputReader(
          sources = sources,
          searchFailure = searchFailure,
          requireOutsideUnitOfWork =
              if (requireSearchOutsideUnitOfWork) {
                { store.requireInactive() }
              } else {
                {}
              },
      )
  val repository = MemoryPresentationRepository(store, failOnGuidelineId, saveFailure)
  val matches = GUIDELINES.map { guideline -> MatchedGuideline(guideline.id, guideline.content) }
  val useCase =
      PresentGuidelinesUseCase(
          guidelineSearchService = GuidelineSearchService(reader),
          unitOfWork = unitOfWork ?: FakePresentationUnitOfWork(store),
          presentationRepository = presentationRepository ?: repository,
          clock = FIXED_CLOCK,
      )

  return PresentationFixture(
      useCase = useCase,
      repository = repository,
      reader = reader,
      store = store,
      matches = matches,
  )
}

private fun request(
    sessionId: String,
    cwd: Path = Path.of("/virtual/project"),
    filepaths: Collection<Path> = listOf(SOURCE.filepath),
): GuidelinePresentationRequest =
    GuidelinePresentationRequest(
        sessionId = SessionId(sessionId),
        cwd = cwd,
        filepaths = filepaths,
    )

private fun presentation(
    sessionId: String,
    guidelineId: String,
    presentedAt: Instant = PRESENTED_AT,
): Presentation =
    Presentation(
        sessionId = SessionId(sessionId),
        guidelineId = GuidelineId(guidelineId),
        presentedAt = presentedAt,
    )

private fun presentationValues(
    presentations: Collection<Presentation>
): List<Triple<SessionId, GuidelineId, Instant>> = presentations.map { presentation ->
  Triple(presentation.sessionId, presentation.guidelineId, presentation.presentedAt)
}

private fun presentationValue(
    sessionId: String,
    guidelineId: String,
    presentedAt: Instant = PRESENTED_AT,
): Triple<SessionId, GuidelineId, Instant> =
    Triple(SessionId(sessionId), GuidelineId(guidelineId), presentedAt)

private fun guidelineWithContent(guideline: Guideline, content: String): Guideline =
    Guideline(
        id = guideline.id,
        scope = guideline.scope,
        events = guideline.events,
        match = guideline.match,
        content = content,
    )

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
