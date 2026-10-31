package com.springkit.agentguidebook.models

import java.nio.file.Path
import java.util.ArrayList
import java.util.Collections

/**
 * 한 프로젝트에서 지침 검색을 수행할 경로 입력입니다.
 *
 * 경로의 절대성은 검색 서비스가 검사하며, 전달한 파일 목록은 변경할 수 없게 복사됩니다.
 *
 * @property cwd 검색 대상 프로젝트 경로
 * @property filepaths 검색 대상으로 요청한 파일 경로의 불변 목록
 */
class GuidelineSearchRequest(
    val cwd: Path,
    filepaths: Collection<Path>,
) {
  val filepaths: List<Path> = Collections.unmodifiableList(ArrayList(filepaths))
}
