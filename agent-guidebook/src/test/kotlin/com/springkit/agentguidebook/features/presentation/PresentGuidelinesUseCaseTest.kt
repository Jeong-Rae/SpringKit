package com.springkit.agentguidebook.features.presentation

import com.springkit.agentguidebook.core.Guideline
import com.springkit.agentguidebook.core.MatchCondition
import com.springkit.agentguidebook.core.MatchedGuideline
import com.springkit.agentguidebook.core.Presentation
import com.springkit.agentguidebook.core.Scope
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
    })

private data class PresentationFixture(
    val useCase: PresentGuidelinesUseCase,
    val repository: MemoryPresentationRepository,
    val reader: FakeGuidelineSearchInputReader,
    val matches: List<MatchedGuideline>,
)

private class FakeGuidelineSearchInputReader(
    var guidelines: List<Guideline> = GUIDELINES,
) : GuidelineSearchInputReader {
  override fun validateWorkingDirectory(cwd: Path) = Unit

  override fun readKotlinSources(filepaths: Collection<Path>): List<SourceFile> =
      listOf(SOURCE).filter { source -> source.filepath in filepaths }

  override fun readGuidelines(root: Path): List<Guideline> = guidelines

  override fun readRepositoryIdentity(cwd: Path): RepositoryIdentity =
      RepositoryIdentity("acme", "backend-order")
}

private class MemoryPresentationStore(initialHistory: Collection<Presentation>) {
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

  fun activePresentations(): MutableList<Presentation> =
      checkNotNull(pending) { "Fake Repository는 활성 UnitOfWork 안에서만 사용할 수 있습니다." }

  fun presentationsFor(sessionId: SessionId): List<Presentation> =
      committed.filter { presentation ->
        presentation.sessionId == sessionId
      }
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
) : PresentationRepository {
  override fun presentations(sessionId: SessionId): List<Presentation> =
      store.activePresentations().filter { presentation -> presentation.sessionId == sessionId }

  override fun record(presentations: Collection<Presentation>) {
    store.activePresentations().addAll(presentations)
  }

  fun presentationsFor(sessionId: SessionId): List<Presentation> = store.presentationsFor(sessionId)
}

private fun fixture(
    initialHistory: Collection<Presentation> = emptyList(),
): PresentationFixture {
  val store = MemoryPresentationStore(initialHistory)
  val reader = FakeGuidelineSearchInputReader()
  val repository = MemoryPresentationRepository(store)
  val matches = GUIDELINES.map { guideline -> MatchedGuideline(guideline.id, guideline.content) }
  val useCase =
      PresentGuidelinesUseCase(
          guidelineSearchService = GuidelineSearchService(reader),
          unitOfWork = FakePresentationUnitOfWork(store),
          presentationRepository = repository,
          clock = FIXED_CLOCK,
      )

  return PresentationFixture(
      useCase = useCase,
      repository = repository,
      reader = reader,
      matches = matches,
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
