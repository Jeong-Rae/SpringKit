package com.springkit.agentguidebook.search.filesystem

import com.springkit.agentguidebook.domain.Language
import com.springkit.agentguidebook.domain.SourceFile
import com.springkit.agentguidebook.search.SourceReadException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal class SourceFileReader {

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
