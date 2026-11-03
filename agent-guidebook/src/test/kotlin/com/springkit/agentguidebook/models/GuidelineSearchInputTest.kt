package com.springkit.agentguidebook.models

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FeatureSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class GuidelineSearchInputTest :
    FeatureSpec({
      feature("검색 요청") {
        scenario("생성에 사용한 파일 경로 목록을 바꾸면, 요청에 보관된 복사본은 유지됩니다.") {
          val suppliedFilepaths = mutableListOf(Path.of("/workspace/Order.kt"))
          val request =
              GuidelineSearchRequest(
                  cwd = Path.of("/workspace"),
                  filepaths = suppliedFilepaths,
              )

          suppliedFilepaths.clear()

          request.filepaths shouldBe listOf(Path.of("/workspace/Order.kt"))
        }

        scenario("공개된 파일 경로 목록을 수정하면, 변경이 거부됩니다.") {
          val request =
              GuidelineSearchRequest(
                  cwd = Path.of("/workspace"),
                  filepaths = listOf(Path.of("/workspace/Order.kt")),
              )

          shouldThrow<UnsupportedOperationException> {
            (request.filepaths as MutableList<Path>).add(Path.of("/workspace/Other.kt"))
          }

          request.filepaths shouldBe listOf(Path.of("/workspace/Order.kt"))
        }
      }
    })
