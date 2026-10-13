package com.springkit.agentguidebook.models

import java.nio.file.Path
import java.util.ArrayList
import java.util.Collections

/** 프로젝트 경로와 검색할 절대 파일 경로 목록입니다. */
class GuidelineSearchRequest(
    val cwd: Path,
    filepaths: Collection<Path>,
) {
  val filepaths: List<Path> = Collections.unmodifiableList(ArrayList(filepaths))
}
