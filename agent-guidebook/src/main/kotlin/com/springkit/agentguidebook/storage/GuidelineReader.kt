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

/**
 * 프로젝트 지침 루트를 탐색하고 YAML 지침을 상대 경로순으로 읽습니다.
 *
 * 여러 파일의 지침 ID가 겹치면 검색 입력으로 제공하지 않습니다.
 */
internal class GuidelineReader {
  private val yamlParser = GuidelineYamlParser()

  /**
   * 지침 루트의 YAML 파일을 읽고 ID 중복을 검증합니다.
   *
   * 루트 디렉터리나 후보 파일이 없으면 빈 목록을 반환합니다.
   *
   * @param root 프로젝트의 `.agents/guides` 경로
   * @return 상대 경로순으로 읽고 ID 중복을 검증한 지침 목록
   * @throws GuidelineLoadException 루트가 디렉터리가 아니거나 파일·지침 검증에 실패한 경우
   */
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

  /**
   * 지침 루트의 파일 속성을 읽고, 경로가 없으면 `null`을 반환합니다.
   *
   * 프로젝트에 `.agents/guides`가 없을 수 있으므로 이 부재는 검색 계약상 정상적인 빈 결과입니다. 이 helper의 책임은 루트 속성 조회까지이며 지침 목록을
   * 만들지 않으므로, 목록을 구성하는 [read]가 반환된 `null`을 빈 목록으로 해석합니다. 그 밖의 I/O 및 권한 실패는 빈 결과로 감추지 않고 지침 로딩 오류로
   * 전달합니다.
   *
   * @param root 속성을 확인할 지침 경로
   * @return 경로 속성 또는 경로 부재를 나타내는 `null`
   * @throws [GuidelineLoadException] 경로 속성을 읽을 수 없는 경우
   */
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
