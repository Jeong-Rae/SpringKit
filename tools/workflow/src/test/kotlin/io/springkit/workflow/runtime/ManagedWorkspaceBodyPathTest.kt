package io.springkit.workflow.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.springkit.workflow.domain.WorkspacePath
import java.nio.file.Files

class ManagedWorkspaceBodyPathTest :
    FunSpec({
      context("관리 Worktree 내부의 본문 경로를 해석할 때") {
        test("정상 상대 경로를 입력하면, 실제 파일 경로를 반환합니다") {
          val workspace = Files.createTempDirectory("workflow-body-workspace-")
          val body = Files.writeString(workspace.resolve("pr.md"), "본문")

          ManagedWorkspaceBodyPath.resolve(WorkspacePath(workspace.toString()), "pr.md") shouldBe
              body.toRealPath()
        }

        test("절대 경로가 Worktree 내부를 가리키면, 실제 파일 경로를 반환합니다") {
          val workspace = Files.createTempDirectory("workflow-body-workspace-")
          val body = Files.writeString(workspace.resolve("pr.md"), "본문")

          ManagedWorkspaceBodyPath.resolve(
              WorkspacePath(workspace.toString()),
              body.toString(),
          ) shouldBe body.toRealPath()
        }
      }

      context("본문 경로가 관리 Worktree 밖을 가리킬 때") {
        test("절대 경로를 입력하면, 잘못된 인자로 거부합니다") {
          val workspace = Files.createTempDirectory("workflow-body-workspace-")
          val outside = Files.createTempFile("workflow-body-outside-", ".md")

          shouldThrow<IllegalArgumentException> {
            ManagedWorkspaceBodyPath.resolve(
                WorkspacePath(workspace.toString()),
                outside.toString(),
            )
          }
        }

        test("상위 경로가 Worktree 밖을 가리키면, 잘못된 인자로 거부합니다") {
          val parent = Files.createTempDirectory("workflow-body-parent-")
          val workspace = Files.createDirectory(parent.resolve("workspace"))
          Files.writeString(parent.resolve("outside.md"), "본문")

          shouldThrow<IllegalArgumentException> {
            ManagedWorkspaceBodyPath.resolve(
                WorkspacePath(workspace.toString()),
                "../outside.md",
            )
          }
        }

        test("심볼릭 링크가 Worktree 밖을 가리키면, 잘못된 인자로 거부합니다") {
          val workspace = Files.createTempDirectory("workflow-body-workspace-")
          val outside = Files.createTempFile("workflow-body-outside-", ".md")
          Files.createSymbolicLink(workspace.resolve("link.md"), outside)

          shouldThrow<IllegalArgumentException> {
            ManagedWorkspaceBodyPath.resolve(WorkspacePath(workspace.toString()), "link.md")
          }
        }
      }
    })
