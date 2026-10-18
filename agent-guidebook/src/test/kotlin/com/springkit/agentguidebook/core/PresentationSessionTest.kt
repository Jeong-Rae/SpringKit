package com.springkit.agentguidebook.core

import com.springkit.agentguidebook.models.GuidelineId
import com.springkit.agentguidebook.models.SessionId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.datatest.withScenarios
import io.kotest.matchers.shouldBe
import java.time.Instant

class PresentationSessionTest :
    FeatureSpec({
      feature("Session의 Presentation 이력 조회") {
        scenario("이력이 없는 Session에서 지침을 조회하면, 입력 순서와 한글 본문을 보존합니다.") {
          val session = session()
          val guidelines =
              listOf(
                  guideline("첫 번째", "첫 번째 지침 본문입니다."),
                  guideline("두 번째", "두 번째 지침 본문입니다."),
              )

          val notPresented = session.notPresented(guidelines)

          notPresented shouldBe guidelines
          notPresented.map { it.id } shouldBe listOf(GuidelineId("첫 번째"), GuidelineId("두 번째"))
          notPresented.map { it.content } shouldBe listOf("첫 번째 지침 본문입니다.", "두 번째 지침 본문입니다.")
        }

        scenario("기존 ID와 새 ID가 함께 검색되면, 기존 ID만 제외하고 입력 순서를 보존합니다.") {
          val session = session(history = listOf(presentation("이미 제공", "2026-10-17T12:00:00Z")))
          val guidelines =
              listOf(
                  guideline("새 지침", "새 지침 본문입니다."),
                  guideline("이미 제공", "수정된 본문입니다."),
                  guideline("다른 새 지침", "다른 새 지침 본문입니다."),
              )

          val notPresented = session.notPresented(guidelines)

          notPresented shouldBe listOf(guidelines[0], guidelines[2])
          notPresented.map { it.content } shouldBe listOf("새 지침 본문입니다.", "다른 새 지침 본문입니다.")
        }

        scenario("조회 입력에 같은 Guideline ID가 반복되면, 첫 항목만 반환합니다.") {
          val session = session()
          val guidelines =
              listOf(
                  guideline("같은 ID", "첫 번째 본문입니다."),
                  guideline("같은 ID", "두 번째 본문입니다."),
              )

          val notPresented = session.notPresented(guidelines)

          notPresented shouldBe listOf(guidelines.first())
        }

        scenario("같은 이력을 반복 조회하면, 조회가 Session 상태를 바꾸지 않습니다.") {
          val session = session(history = listOf(presentation("이미 제공", "2026-10-17T12:00:00Z")))
          val guidelines =
              listOf(
                  guideline("이미 제공", "이미 제공된 지침입니다."),
                  guideline("새 지침", "아직 제공하지 않은 지침입니다."),
              )

          val firstResult = session.notPresented(guidelines)
          val secondResult = session.notPresented(guidelines)

          firstResult shouldBe listOf(guidelines[1])
          secondResult shouldBe firstResult
          session.notPresented(guidelines) shouldBe firstResult
        }

        scenario("생성에 사용한 이력 목록을 변경하면, Session의 이력은 유지됩니다.") {
          val suppliedHistory = mutableListOf(presentation("기존 지침", "2026-10-17T12:00:00Z"))
          val session = session(history = suppliedHistory)
          val guidelines =
              listOf(
                  guideline("기존 지침", "이미 제공된 본문입니다."),
                  guideline("새 지침", "새 본문입니다."),
              )

          suppliedHistory.clear()
          suppliedHistory.add(presentation("새 지침", "2026-10-16T12:00:00Z"))

          session.notPresented(guidelines) shouldBe listOf(guidelines[1])
        }

        scenario("조회 입력 목록을 전달하면, Session은 입력 컬렉션을 변경하지 않습니다.") {
          val session = session()
          val suppliedGuidelines =
              mutableListOf(
                  guideline("첫 번째", "첫 번째 본문입니다."),
                  guideline("두 번째", "두 번째 본문입니다."),
              )
          val originalGuidelines = suppliedGuidelines.toList()

          session.notPresented(suppliedGuidelines)

          suppliedGuidelines shouldBe originalGuidelines
        }
      }

      feature("Session의 Presentation 기록") {
        scenario("새 기록을 추가하면, 다음 미제공 조회에서 해당 ID를 제외합니다.") {
          val session = session()
          val guidelines =
              listOf(
                  guideline("기록할 지침", "기록할 본문입니다."),
                  guideline("남은 지침", "남은 본문입니다."),
              )
          val presentations = listOf(presentation("기록할 지침", "2026-10-19T12:00:00Z"))

          session.record(presentations)

          session.notPresented(guidelines) shouldBe listOf(guidelines[1])
        }

        scenario("기록에 사용한 입력 목록을 변경하면, Session의 이력은 유지됩니다.") {
          val session = session()
          val suppliedPresentations = mutableListOf(presentation("기록된 지침", "2026-10-17T12:00:00Z"))
          val guidelines =
              listOf(
                  guideline("기록된 지침", "기록된 본문입니다."),
                  guideline("추가된 입력", "추가된 본문입니다."),
              )

          session.record(suppliedPresentations)
          suppliedPresentations.clear()
          suppliedPresentations.add(presentation("추가된 입력", "2026-10-16T12:00:00Z"))

          session.notPresented(guidelines) shouldBe listOf(guidelines[1])
        }

        scenario("빈 기록을 추가하면, 기존 Session 이력을 유지합니다.") {
          val session = session(history = listOf(presentation("이미 제공", "2026-10-17T12:00:00Z")))
          val guidelines =
              listOf(
                  guideline("이미 제공", "기존 본문입니다."),
                  guideline("새 지침", "새 본문입니다."),
              )

          session.record(emptyList())

          session.notPresented(guidelines) shouldBe listOf(guidelines[1])
        }

        withScenarios(
            nameFn = { it.name },
            listOf(
                InvalidRecordingScenario(
                    name = "다른 Session의 기록을 새 기록과 함께 추가하면, 전체 명령을 거부합니다.",
                    presentations =
                        listOf(
                            presentation("새 지침"),
                            presentation("다른 Session 지침", sessionId = "다른 세션"),
                        ),
                ),
                InvalidRecordingScenario(
                    name = "같은 Guideline ID를 한 명령에 반복하면, 전체 명령을 거부합니다.",
                    presentations =
                        listOf(
                            presentation("새 지침"),
                            presentation("다른 새 지침"),
                            presentation("새 지침", presentedAt = "2026-10-17T12:00:00Z"),
                        ),
                ),
            ),
        ) { scenario ->
          val session = session()
          val guidelines =
              listOf(
                  guideline("새 지침", "새 지침 본문입니다."),
                  guideline("다른 새 지침", "다른 새 지침 본문입니다."),
                  guideline("다른 Session 지침", "다른 Session 본문입니다."),
              )

          shouldThrow<IllegalArgumentException> {
            session.record(scenario.presentations)
          }

          session.notPresented(guidelines) shouldBe guidelines
        }

        scenario("기존 ID와 새 ID가 섞인 기록에서 기존 ID가 있으면, 전체 명령을 거부합니다.") {
          val session = session(history = listOf(presentation("이미 제공", "2026-10-17T12:00:00Z")))
          val presentations =
              listOf(
                  presentation("새 지침"),
                  presentation("이미 제공", presentedAt = "2026-10-19T12:00:00Z"),
              )
          val guidelines =
              listOf(
                  guideline("새 지침", "새 지침 본문입니다."),
                  guideline("이미 제공", "이미 제공한 본문입니다."),
              )

          shouldThrow<IllegalStateException> {
            session.record(presentations)
          }

          session.notPresented(guidelines) shouldBe listOf(guidelines[0])
        }
      }

      feature("Session 이력 생성 검증") {
        withScenarios(
            nameFn = { it.name },
            listOf(
                InvalidHistoryScenario(
                    name = "다른 Session의 이력을 전달해 Session을 만들면, 생성이 거부됩니다.",
                    history = listOf(presentation("지침", sessionId = "다른 세션")),
                ),
                InvalidHistoryScenario(
                    name = "시각이 달라도 Guideline ID가 같은 이력을 전달하면, 생성이 거부됩니다.",
                    history =
                        listOf(
                            presentation("중복 ID", presentedAt = "2026-10-17T12:00:00Z"),
                            presentation("중복 ID", presentedAt = "2026-10-18T12:00:00Z"),
                        ),
                ),
            ),
        ) { scenario ->
          shouldThrow<IllegalArgumentException> {
            session(history = scenario.history)
          }
        }
      }
    })

private data class InvalidRecordingScenario(
    val name: String,
    val presentations: List<Presentation>,
)

private data class InvalidHistoryScenario(
    val name: String,
    val history: List<Presentation>,
)

private fun session(
    sessionId: String = "codex-session",
    history: Collection<Presentation> = emptyList(),
): PresentationSession = PresentationSession(SessionId(sessionId), history)

private fun presentation(
    guidelineId: String,
    presentedAt: String = "2026-10-18T12:00:00Z",
    sessionId: String = "codex-session",
): Presentation =
    Presentation(
        sessionId = SessionId(sessionId),
        guidelineId = GuidelineId(guidelineId),
        presentedAt = Instant.parse(presentedAt),
    )

private fun guideline(id: String, content: String): MatchedGuideline =
    MatchedGuideline(id = GuidelineId(id), content = content)
