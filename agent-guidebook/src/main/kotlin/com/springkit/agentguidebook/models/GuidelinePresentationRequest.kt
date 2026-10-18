package com.springkit.agentguidebook.models

import java.nio.file.Path

class GuidelinePresentationRequest(
    val sessionId: SessionId,
    val cwd: Path,
    filepaths: Collection<Path>,
) {
  val filepaths: List<Path> = throw NotImplementedError("Presentation 요청의 파일 경로 복사는 아직 구현되지 않았습니다.")
}
