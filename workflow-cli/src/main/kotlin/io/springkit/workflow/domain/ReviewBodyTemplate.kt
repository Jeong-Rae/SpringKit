package io.springkit.workflow.domain

/** PR 본문에 필요한 Markdown 제목과 순서를 검증합니다. */
object ReviewBodyTemplate {
  private val requiredHeadings =
      listOf(
          "해결하려는 문제",
          "왜 지금 해결해야 하는가",
          "어떻게 해결했는가",
          "한계와 트레이드오프",
          "기존 기능에 미치는 영향",
          "Edge Case와 실패 시나리오",
          "검토한 대안과 선택 이유",
          "리뷰 포인트",
      )

  /** 첫 번째 누락 또는 순서 오류를 설명하며, 계약을 만족하면 null을 반환합니다. */
  fun validate(body: String): String? {
    var fence: CodeFence? = null
    val headings =
        body
            .lineSequence()
            .mapNotNull { line ->
              val currentFence = fence
              if (currentFence != null) {
                if (line.closes(currentFence)) fence = null
                null
              } else {
                fence = line.opensFence()
                if (fence == null) heading(line) else null
              }
            }
            .toList()
    var nextIndex = 0

    requiredHeadings.forEachIndexed { requiredIndex, requiredHeading ->
      val matchIndex =
          (nextIndex until headings.size).firstOrNull { headings[it] == requiredHeading } ?: -1
      if (matchIndex >= 0) {
        nextIndex = matchIndex + 1
      } else {
        val previousHeading = requiredHeadings.getOrNull(requiredIndex - 1)
        val appearsEarlier = headings.indexOf(requiredHeading) >= 0
        return if (appearsEarlier && previousHeading != null) {
          "PR 본문 제목 순서가 잘못되었습니다. '$previousHeading' 다음에 '$requiredHeading' 제목이 있어야 합니다."
        } else {
          "PR 본문에 '$requiredHeading' 제목이 없습니다."
        }
      }
    }

    return null
  }

  private fun heading(line: String): String? {
    val match = Regex("^ {0,3}#{1,6}[ \\t]+(.+?)[ \\t]*$").matchEntire(line) ?: return null
    return match.groupValues[1].replace(Regex("[ \\t]+#+[ \\t]*$"), "").trim()
  }

  private fun String.opensFence(): CodeFence? {
    val match = Regex("^ {0,3}(`{3,}|~{3,}).*$").matchEntire(this) ?: return null
    val marker = match.groupValues[1]
    return CodeFence(marker.first(), marker.length)
  }

  private fun String.closes(fence: CodeFence): Boolean {
    val match = Regex("^ {0,3}([`~]+)[ \\t]*$").matchEntire(this) ?: return false
    val marker = match.groupValues[1]
    return marker.all { it == fence.character } && marker.length >= fence.length
  }

  private data class CodeFence(val character: Char, val length: Int)
}
