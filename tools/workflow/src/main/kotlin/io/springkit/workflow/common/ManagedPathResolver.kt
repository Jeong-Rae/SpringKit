package io.springkit.workflow.common

import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque

/*
 * 관리 root 경계 검사를 위해 파일 시스템 경로를 실제 경로 기준으로 해석합니다.
 */
object ManagedPathResolver {
  /*
   * 존재하는 가장 가까운 조상 경로를 실제 경로로 해석한 뒤, 존재하지 않는 suffix를 붙입니다.
   */
  fun canonicalize(path: Path): Path {
    val normalized = path.toAbsolutePath().normalize()
    val suffix = ArrayDeque<Path>()
    var existing = normalized
    while (!Files.exists(existing)) {
      val name = existing.fileName ?: return normalized
      suffix.addFirst(name)
      existing = existing.parent ?: return normalized
    }
    val canonicalExisting =
        runCatching { existing.toRealPath() }
            .getOrElse {
              return normalized
            }
    return suffix.fold(canonicalExisting) { current, name -> current.resolve(name) }.normalize()
  }

  /*
   * 경로를 managed root 기준으로 해석하고 root 밖이면 null을 반환합니다.
   */
  fun resolveWithin(root: Path, path: Path): Path? {
    val canonicalRoot = canonicalize(root)
    val candidate = if (path.isAbsolute) path else canonicalRoot.resolve(path)
    val canonicalCandidate = canonicalize(candidate)
    return canonicalCandidate.takeIf {
      it == canonicalRoot || it.startsWith(canonicalRoot)
    }
  }
}
