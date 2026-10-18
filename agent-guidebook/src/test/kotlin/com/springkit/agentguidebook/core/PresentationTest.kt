package com.springkit.agentguidebook.core

import com.springkit.agentguidebook.models.GuidelineId
import com.springkit.agentguidebook.models.SessionId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class PresentationTest :
    FeatureSpec({
      feature("Presentation의 업무상 식별 기준") {
        scenario("같은 Session과 Guideline ID의 기록은 시각이 달라도 중복입니다.") {
          val sessionId = SessionId("codex-session")
          val firstPresentation =
              Presentation(
                  sessionId = sessionId,
                  guidelineId = GuidelineId("repository-rule"),
                  presentedAt = Instant.parse("2026-10-18T12:00:00Z"),
              )
          val session = PresentationSession(sessionId, listOf(firstPresentation))
          val laterPresentation =
              Presentation(
                  sessionId = sessionId,
                  guidelineId = GuidelineId("repository-rule"),
                  presentedAt = Instant.parse("2026-10-19T12:00:00Z"),
              )

          shouldThrow<IllegalStateException> {
            session.record(listOf(laterPresentation))
          }

          session.notPresented(listOf(guideline("repository-rule"))) shouldBe emptyList()
        }

        scenario("같은 Guideline ID를 다른 Session에서 요청하면, 새 Session에서 제공할 수 있습니다.") {
          val firstSessionId = SessionId("codex-session-one")
          val secondSessionId = SessionId("codex-session-two")
          val firstSession =
              PresentationSession(
                  firstSessionId,
                  listOf(
                      Presentation(
                          sessionId = firstSessionId,
                          guidelineId = GuidelineId("repository-rule"),
                          presentedAt = Instant.parse("2026-10-18T12:00:00Z"),
                      )
                  ),
              )
          val secondSession = PresentationSession(secondSessionId, emptyList())
          val guideline = guideline("repository-rule")

          firstSession.notPresented(listOf(guideline)) shouldBe emptyList()
          secondSession.notPresented(listOf(guideline)) shouldBe listOf(guideline)
        }
      }
    })

private fun guideline(id: String): MatchedGuideline =
    MatchedGuideline(
        id = GuidelineId(id),
        content = "저장소 추상화에 의존해야 합니다.",
    )
