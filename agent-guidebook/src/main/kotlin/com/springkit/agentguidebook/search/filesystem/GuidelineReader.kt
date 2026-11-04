package com.springkit.agentguidebook.search.filesystem

import com.springkit.agentguidebook.domain.Guideline
import com.springkit.agentguidebook.domain.GuidelineId
import com.springkit.agentguidebook.search.GuidelineLoadException
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal class GuidelineReader {
  private val yamlParser = GuidelineYamlParser()

  fun read(root: Path): List<Guideline> {
    val rootAttributes = readRootAttributes(root) ?: return emptyList()
    if (!rootAttributes.isDirectory) {
      throw GuidelineLoadException("지침 경로가 디렉터리가 아닙니다: $root")
    }

    val files = findGuidelineFiles(root)
    val guidelines = files.map(yamlParser::read)
    validateUniqueIds(guidelines, root)

    return guidelines
  }

  /** 경로가 없으면 검색 부재를 나타내는 `null`을 반환하고 [read]가 빈 목록으로 해석합니다. 다른 I/O 오류는 숨기지 않습니다. */
  private fun readRootAttributes(root: Path): BasicFileAttributes? =
      try {
        Files.readAttributes(root, BasicFileAttributes::class.java)
      } catch (exception: NoSuchFileException) {
        null
      } catch (exception: IOException) {
        throw GuidelineLoadException("지침 경로를 확인할 수 없습니다: $root", exception)
      } catch (exception: SecurityException) {
        throw GuidelineLoadException("지침 경로를 확인할 수 없습니다: $root", exception)
      }

  private fun findGuidelineFiles(root: Path): List<Path> =
      try {
        Files.walk(root).use { paths ->
          paths
              .filter { filepath ->
                Files.isRegularFile(filepath) && filepath.fileName.toString() == GUIDELINE_FILENAME
              }
              .toList()
              .sortedBy { filepath -> relativePath(root, filepath) }
        }
      } catch (exception: IOException) {
        throw GuidelineLoadException("지침 파일을 찾을 수 없습니다: $root", exception)
      } catch (exception: UncheckedIOException) {
        throw GuidelineLoadException("지침 파일을 찾을 수 없습니다: $root", exception.cause)
      } catch (exception: SecurityException) {
        throw GuidelineLoadException("지침 파일을 찾을 수 없습니다: $root", exception)
      }

  private fun validateUniqueIds(guidelines: List<Guideline>, root: Path) {
    val seenIds = HashSet<GuidelineId>()

    for (guideline in guidelines) {
      val isDuplicateGuidelineId = guideline.id in seenIds
      if (isDuplicateGuidelineId) {
        throw GuidelineLoadException("지침 ID가 중복되었습니다: ${guideline.id.value} ($root)")
      }

      seenIds.add(guideline.id)
    }
  }

  private fun relativePath(root: Path, filepath: Path): String =
      root.relativize(filepath).toString().replace(File.separatorChar, '/')

  private companion object {
    const val GUIDELINE_FILENAME = "guideline.yaml"
  }
}
