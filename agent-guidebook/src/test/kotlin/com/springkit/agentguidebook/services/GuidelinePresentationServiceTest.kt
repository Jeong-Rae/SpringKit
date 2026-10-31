package com.springkit.agentguidebook.services

import com.springkit.agentguidebook.core.Guideline
import com.springkit.agentguidebook.core.MatchCondition
import com.springkit.agentguidebook.core.MatchedGuideline
import com.springkit.agentguidebook.core.Presentation
import com.springkit.agentguidebook.core.Scope
import com.springkit.agentguidebook.errors.GuidelineLoadException
import com.springkit.agentguidebook.errors.RetryablePresentationStorageException
import com.springkit.agentguidebook.models.GuidelineEvent
import com.springkit.agentguidebook.models.GuidelineId
import com.springkit.agentguidebook.models.GuidelinePresentationRequest
import com.springkit.agentguidebook.models.Language
import com.springkit.agentguidebook.models.RepositoryIdentity
import com.springkit.agentguidebook.models.SessionId
import com.springkit.agentguidebook.models.SourceFile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class GuidelinePresentationServiceTest :
    FeatureSpec({
      feature("Session별 지침 제공") {
        scenario("처음 요청하면 검색 순서대로 모든 지침을 반환하고 같은 시각으로 기록합니다.") {
          val fixture = fixture()
          val request = request("session-a")

          val result = fixture.service.present(request)

          result shouldContainExactly fixture.matches
          presentationValues(
              fixture.repository.presentationsFor(SessionId("session-a"))
          ) shouldContainExactly fixture.matches.map { presentationValue("session-a", it.id.value) }
        }

        scenario("같은 Session에서 반복 요청하면 이미 제공한 지침을 다시 반환하지 않습니다.") {
          val fixture = fixture()
          val request = request("session-a")

          fixture.service.present(request)
          val repeated = fixture.service.present(request)

          repeated shouldBe emptyList()
          presentationValues(
              fixture.repository.presentationsFor(SessionId("session-a"))
          ) shouldContainExactly fixture.matches.map { presentationValue("session-a", it.id.value) }
        }

        scenario("기존 일부 이력만 제외하고 나머지를 검색 순서대로 기록합니다.") {
          val earlierTime = Instant.parse("2026-10-17T12:00:00Z")
          val alreadyPresented = presentation("session-a", "second-rule", earlierTime)
          val fixture = fixture(initialHistory = listOf(alreadyPresented))

          val result = fixture.service.present(request("session-a"))

          result shouldContainExactly listOf(fixture.matches.first())
          presentationValues(
              fixture.repository.presentationsFor(SessionId("session-a"))
          ) shouldContainExactly
              listOf(
                  presentationValue("session-a", "second-rule", earlierTime),
                  presentationValue("session-a", "first-rule"),
              )
        }

        scenario("다른 Session에는 같은 지침을 새로 제공하고 각 이력을 분리합니다.") {
          val fixture = fixture()

          val firstSession = fixture.service.present(request("session-a"))
          val secondSession = fixture.service.present(request("session-b"))

          firstSession shouldContainExactly fixture.matches
          secondSession shouldContainExactly fixture.matches
          presentationValues(
              fixture.repository.presentationsFor(SessionId("session-a"))
          ) shouldContainExactly fixture.matches.map { presentationValue("session-a", it.id.value) }
          presentationValues(
              fixture.repository.presentationsFor(SessionId("session-b"))
          ) shouldContainExactly fixture.matches.map { presentationValue("session-b", it.id.value) }
        }

        scenario("이미 제공한 ID의 본문이 검색 결과에서 바뀌어도 다시 제공하지 않습니다.") {
          val fixture = fixture()
          fixture.service.present(request("session-a"))
          fixture.reader.guidelines =
              fixture.reader.guidelines.map { guideline ->
                if (guideline.id.value == "first-rule") {
                  guidelineWithContent(guideline, "수정된 한글 지침 본문입니다.")
                } else {
                  guideline
                }
              }

          val result = fixture.service.present(request("session-a"))

          result shouldBe emptyList()
          fixture.repository.presentationsFor(SessionId("session-a")) shouldHaveSize 2
        }

        scenario("요청 생성 뒤 외부 경로 목록을 바꾸어도 검색 입력은 요청 시점 값을 사용합니다.") {
          val fixture = fixture()
          val paths = mutableListOf(Path.of("/virtual/project/src/OrderService.kt"))
          val request =
              GuidelinePresentationRequest(
                  sessionId = SessionId("session-a"),
                  cwd = Path.of("/virtual/project"),
                  filepaths = paths,
              )
          paths.clear()

          val result = fixture.service.present(request)

          result shouldContainExactly fixture.matches
          paths shouldBe emptyList()
        }
      }

      feature("검색과 저장 경계의 실패") {
        scenario("검색 결과가 비어 있으면 저장소를 열지 않고 빈 목록을 반환합니다.") {
          val reader = FakeGuidelineSearchInputReader(sources = emptyList())
          val service =
              GuidelinePresentationService(
                  guidelineSearchService = GuidelineSearchService(reader),
                  unitOfWork = UnexpectedPresentationUnitOfWork,
                  presentationRepository = UnexpectedPresentationRepository,
                  clock = FIXED_CLOCK,
              )

          val result = service.present(request("session-a"))

          result shouldBe emptyList()
        }

        scenario("검색 오류는 원래 예외 타입과 이력을 보존합니다.") {
          val failure = GuidelineLoadException("지침 파일을 읽을 수 없습니다.")
          val fixture =
              fixture(
                  initialHistory = listOf(presentation("session-a", "older-rule")),
                  searchFailure = failure,
              )
          val before = presentationValues(fixture.repository.allPresentations())

          val thrown =
              shouldThrow<GuidelineLoadException> {
                fixture.service.present(request("session-a"))
              }

          thrown shouldBe failure
          presentationValues(fixture.repository.allPresentations()) shouldBe before
        }

        scenario("일부 기록 후 저장 실패가 나면 전체 이력을 rollback하고 성공 목록을 반환하지 않습니다.") {
          val fixture =
              fixture(
                  persistence =
                      memoryPresentationPersistence(
                          failOnGuidelineId = GuidelineId("second-rule"),
                          saveFailure = RetryablePresentationStorageException(),
                      )
              )

          shouldThrow<RetryablePresentationStorageException> {
            fixture.service.present(request("session-a"))
          }

          fixture.repository.allPresentations() shouldBe emptyList()
        }

        scenario("commit 실패가 나면 기록을 rollback하고 성공 목록을 반환하지 않습니다.") {
          val fixture =
              fixture(
                  persistence =
                      memoryPresentationPersistence(
                          commitFailure = RetryablePresentationStorageException("commit 실패")
                      )
              )

          shouldThrow<RetryablePresentationStorageException> {
            fixture.service.present(request("session-a"))
          }

          fixture.repository.allPresentations() shouldBe emptyList()
        }

        scenario("저장 callback 자체의 오류는 원래 타입을 유지하고 이력을 rollback합니다.") {
          val failure = IllegalArgumentException("저장 callback 입력이 올바르지 않습니다.")
          val fixture =
              fixture(
                  persistence =
                      memoryPresentationPersistence(
                          initialHistory = listOf(presentation("session-a", "older-rule")),
                          readFailure = failure,
                      ),
              )
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
    val service: GuidelinePresentationService,
    val repository: MemoryPresentationRepository,
    val reader: FakeGuidelineSearchInputReader,
    val matches: List<MatchedGuideline>,
)

private data class MemoryPresentationPersistence(
    val unitOfWork: FakePresentationUnitOfWork,
    val repository: MemoryPresentationRepository,
    val store: MemoryPresentationStore,
)

private class FakeGuidelineSearchInputReader(
    private val sources: List<SourceFile> = listOf(SOURCE),
    var guidelines: List<Guideline> = GUIDELINES,
    private val searchFailure: RuntimeException? = null,
    private val requireOutsideUnitOfWork: () -> Unit = {},
) : GuidelineSearchInputReader {
  override fun validateWorkingDirectory(cwd: Path) {
    requireOutsideUnitOfWork()
  }

  override fun readKotlinSources(filepaths: Collection<Path>): List<SourceFile> {
    requireOutsideUnitOfWork()
    return sources.filter { source ->
      source.filepath in filepaths
    }
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
    initialHistory: Collection<Presentation> = emptyList(),
    private val commitFailure: RuntimeException? = null,
    private val readFailure: RuntimeException? = null,
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

  fun requireInactive() {
    check(pending == null) { "Guideline 검색은 UnitOfWork transaction 밖에서 수행해야 합니다." }
  }

  fun activePresentations(): MutableList<Presentation> =
      checkNotNull(pending) { "Fake Repository는 활성 UnitOfWork 안에서만 사용할 수 있습니다." }

  fun presentationsFor(sessionId: SessionId): List<Presentation> = committed.filter {
    it.sessionId == sessionId
  }

  fun allPresentations(): List<Presentation> = committed.toList()

  fun readFailure(): RuntimeException? = readFailure
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
  override fun presentations(sessionId: SessionId): List<Presentation> {
    store.readFailure()?.let { throw it }
    return store.activePresentations().filter { it.sessionId == sessionId }
  }

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
      error("빈 검색 결과에서는 Presentation 이력을 조회하지 않아야 합니다.")

  override fun record(presentations: Collection<Presentation>) {
    error("빈 검색 결과에서는 Presentation 이력을 기록하지 않아야 합니다.")
  }
}

private object UnexpectedPresentationUnitOfWork : UnitOfWork {
  override fun <T> execute(operation: () -> T): T =
      error("빈 검색 결과에서는 Presentation UnitOfWork를 실행하지 않아야 합니다.")
}

private fun fixture(
    initialHistory: Collection<Presentation> = emptyList(),
    persistence: MemoryPresentationPersistence = memoryPresentationPersistence(initialHistory),
    searchFailure: RuntimeException? = null,
): PresentationServiceFixture {
  val reader =
      FakeGuidelineSearchInputReader(
          searchFailure = searchFailure,
          requireOutsideUnitOfWork = persistence.store::requireInactive,
      )
  val matches = GUIDELINES.map { guideline -> MatchedGuideline(guideline.id, guideline.content) }
  val service =
      GuidelinePresentationService(
          guidelineSearchService = GuidelineSearchService(reader),
          unitOfWork = persistence.unitOfWork,
          presentationRepository = persistence.repository,
          clock = FIXED_CLOCK,
      )

  return PresentationServiceFixture(service, persistence.repository, reader, matches)
}

private fun memoryPresentationPersistence(
    initialHistory: Collection<Presentation> = emptyList(),
    failOnGuidelineId: GuidelineId? = null,
    saveFailure: RuntimeException? = null,
    commitFailure: RuntimeException? = null,
    readFailure: RuntimeException? = null,
): MemoryPresentationPersistence {
  val store = MemoryPresentationStore(initialHistory, commitFailure, readFailure)

  return MemoryPresentationPersistence(
      unitOfWork = FakePresentationUnitOfWork(store),
      repository = MemoryPresentationRepository(store, failOnGuidelineId, saveFailure),
      store = store,
  )
}

private fun request(sessionId: String): GuidelinePresentationRequest =
    GuidelinePresentationRequest(
        sessionId = SessionId(sessionId),
        cwd = Path.of("/virtual/project"),
        filepaths = listOf(SOURCE.filepath),
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
