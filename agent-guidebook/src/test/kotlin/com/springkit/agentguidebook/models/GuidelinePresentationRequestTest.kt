package com.springkit.agentguidebook.models

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class GuidelinePresentationRequestTest :
    FeatureSpec({
      feature("Presentation 요청 입력") {
        scenario("요청 생성 뒤 원본 파일 경로 목록을 바꾸면, 요청 목록은 유지됩니다.") {
          val suppliedFilepaths = mutableListOf(Path.of("/workspace/project/src/Order.kt"))
          val request =
              GuidelinePresentationRequest(
                  sessionId = SessionId("codex-session"),
                  cwd = Path.of("/workspace/project"),
                  filepaths = suppliedFilepaths,
              )

          suppliedFilepaths.clear()
          suppliedFilepaths.add(Path.of("/workspace/project/src/Other.kt"))

          request.filepaths shouldBe listOf(Path.of("/workspace/project/src/Order.kt"))
        }

        scenario("요청의 파일 경로 목록을 외부에서 수정하면, 수정이 거부됩니다.") {
          val filepath = Path.of("/workspace/project/src/Order.kt")
          val request =
              GuidelinePresentationRequest(
                  sessionId = SessionId("codex-session"),
                  cwd = Path.of("/workspace/project"),
                  filepaths = listOf(filepath),
              )

          shouldThrow<UnsupportedOperationException> {
            (request.filepaths as MutableList<Path>).add(Path.of("/workspace/project/src/Other.kt"))
          }

          request.filepaths shouldBe listOf(filepath)
        }

        scenario("요청을 만들면, Session 식별자와 경로 값을 그대로 보존합니다.") {
          val sessionId = SessionId("codex-session-123")
          val cwd = Path.of("/workspace/project/../project")
          val filepaths = listOf(Path.of("/workspace/project/src/../Order.kt"))
          val request =
              GuidelinePresentationRequest(
                  sessionId = sessionId,
                  cwd = cwd,
                  filepaths = filepaths,
              )

          request.sessionId shouldBe sessionId
          request.cwd shouldBe cwd
          request.filepaths shouldBe filepaths
        }
      }
    })
