package com.springkit.agentguidebook.storage

import com.springkit.agentguidebook.core.Guideline
import com.springkit.agentguidebook.errors.GuidelineLoadException
import com.springkit.agentguidebook.models.GuidelineId
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** 프로젝트의 `.agents/guides` 아래에 있는 지침 파일을 정렬해 읽습니다. */
internal class GuidelineReader {
  private val yamlParser = GuidelineYamlParser()

  /** 지침 디렉터리가 없거나 후보가 없으면 빈 목록을 반환합니다. */
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

  private fun relativePath(root: Path, filepath: Path): String =
      root.relativize(filepath).toString().replace(File.separatorChar, '/')

  private fun validateUniqueIds(guidelines: List<Guideline>, root: Path) {
    val seenIds = HashSet<GuidelineId>()

    for (guideline in guidelines) {
      if (!seenIds.add(guideline.id)) {
        throw GuidelineLoadException("지침 ID가 중복되었습니다: ${guideline.id.value} ($root)")
      }
    }
  }

  private companion object {
    const val GUIDELINE_FILENAME = "guideline.yaml"
  }
}
