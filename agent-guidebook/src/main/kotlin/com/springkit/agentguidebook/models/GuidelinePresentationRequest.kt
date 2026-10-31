package com.springkit.agentguidebook.models

import java.nio.file.Path
import java.util.Collections

/**
 * 지침 제공에 필요한 Session과 작업 디렉터리, 대상 파일 경로를 담습니다.
 *
 * 요청을 만든 뒤 호출자가 경로 컬렉션을 바꾸어도 요청 값은 유지됩니다.
 */
class GuidelinePresentationRequest(
    val sessionId: SessionId,
    val cwd: Path,
    filepaths: Collection<Path>,
) {
  val filepaths: List<Path> = Collections.unmodifiableList(ArrayList(filepaths))
}
