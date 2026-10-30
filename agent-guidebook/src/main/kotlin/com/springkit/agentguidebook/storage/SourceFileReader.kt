package com.springkit.agentguidebook.storage

import com.springkit.agentguidebook.errors.SourceReadException
import com.springkit.agentguidebook.models.Language
import com.springkit.agentguidebook.models.SourceFile
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Kotlin 소스 파일을 엄격한 UTF-8 디코딩으로 읽습니다.
 *
 * 읽기 또는 디코딩 실패는 검색 소스 오류로 전달합니다.
 */
internal class SourceFileReader {

  /**
   * `.kt`와 `.kts` 파일을 입력 순서대로 한 번씩 읽습니다.
   *
   * 지원하지 않는 확장자는 결과에서 제외합니다.
   */
  fun readKotlinSources(filepaths: Collection<Path>): List<SourceFile> =
      filepaths.filter(::isKotlinSource).map(::readKotlinSource)

  private fun isKotlinSource(filepath: Path): Boolean {
    val filename = filepath.fileName?.toString() ?: return false
    return filename.endsWith(".kt") || filename.endsWith(".kts")
  }

  private fun readKotlinSource(filepath: Path): SourceFile {
    val isRegularFile =
        try {
          Files.isRegularFile(filepath)
        } catch (exception: SecurityException) {
          throw SourceReadException("검색 대상 Kotlin 파일을 확인할 수 없습니다: $filepath", exception)
        }

    if (!isRegularFile) {
      throw SourceReadException("검색 대상 Kotlin 파일이 없거나 일반 파일이 아닙니다: $filepath")
    }

    val content =
        try {
          Files.readString(filepath, StandardCharsets.UTF_8)
        } catch (exception: IOException) {
          throw SourceReadException("검색 대상 Kotlin 파일을 읽을 수 없습니다: $filepath", exception)
        } catch (exception: SecurityException) {
          throw SourceReadException("검색 대상 Kotlin 파일을 읽을 수 없습니다: $filepath", exception)
        }

    return SourceFile(filepath, content, KOTLIN)
  }

  private companion object {
    val KOTLIN = Language("kotlin")
  }
}
