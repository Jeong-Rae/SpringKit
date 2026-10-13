package com.springkit.agentguidebook.storage

import com.springkit.agentguidebook.errors.RepositoryIdentityUnavailableException
import com.springkit.agentguidebook.models.RepositoryIdentity
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Locale

/** Git 저장소의 `origin` 주소를 정규화된 소유자와 저장소 이름으로 변환합니다. */
internal class RepositoryIdentityReader {

  /** `git remote get-url origin` 결과에서 저장소 식별자를 읽습니다. */
  fun read(cwd: Path): RepositoryIdentity = normalizeOrigin(readOrigin(cwd))

  private fun readOrigin(cwd: Path): String {
    val process =
        try {
          ProcessBuilder("git", "-C", cwd.toString(), "remote", "get-url", "origin")
              .redirectErrorStream(true)
              .start()
        } catch (exception: IOException) {
          throw RepositoryIdentityUnavailableException("저장소 origin을 확인할 수 없습니다.", exception)
        } catch (exception: SecurityException) {
          throw RepositoryIdentityUnavailableException("저장소 origin을 확인할 수 없습니다.", exception)
        }

    val output =
        try {
          process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (exception: IOException) {
          process.destroyForcibly()
          throw RepositoryIdentityUnavailableException("저장소 origin을 읽을 수 없습니다.", exception)
        }

    val exitCode =
        try {
          process.waitFor()
        } catch (exception: InterruptedException) {
          Thread.currentThread().interrupt()
          process.destroyForcibly()
          throw RepositoryIdentityUnavailableException("저장소 origin 조회가 중단되었습니다.", exception)
        }

    if (exitCode != 0) {
      val cause = IllegalStateException("Git origin 조회가 실패했습니다: ${output.trim()}")
      throw RepositoryIdentityUnavailableException("저장소 origin을 확인할 수 없습니다.", cause)
    }

    if (output.isBlank()) {
      val cause = IllegalArgumentException("git remote get-url origin 결과가 비어 있습니다.")
      throw RepositoryIdentityUnavailableException("저장소 origin 주소가 비어 있습니다.", cause)
    }

    return output.trim()
  }

  private fun normalizeOrigin(origin: String): RepositoryIdentity =
      try {
        val (owner, repository) = originComponents(origin)
        RepositoryIdentity(owner, repository.removeSuffix(".git"))
      } catch (exception: URISyntaxException) {
        throw RepositoryIdentityUnavailableException("저장소 origin 주소를 정규화할 수 없습니다.", exception)
      } catch (exception: IllegalArgumentException) {
        throw RepositoryIdentityUnavailableException("저장소 origin 주소를 정규화할 수 없습니다.", exception)
      }

  private fun originComponents(origin: String): Pair<String, String> {
    if (origin.contains(URI_SEPARATOR)) {
      return uriComponents(origin)
    }

    return scpComponents(origin)
  }

  private fun uriComponents(origin: String): Pair<String, String> {
    val uri = URI(origin)
    val scheme = uri.scheme?.lowercase(Locale.ROOT)

    if (scheme != HTTPS_SCHEME && scheme != SSH_SCHEME) {
      throw IllegalArgumentException("HTTPS 또는 SSH origin만 지원합니다.")
    }
    if (uri.host.isNullOrBlank()) {
      throw IllegalArgumentException("origin 호스트가 비어 있습니다.")
    }
    if (uri.rawQuery != null || uri.rawFragment != null) {
      throw IllegalArgumentException("origin에 쿼리나 fragment를 지정할 수 없습니다.")
    }

    return ownerAndRepository(uri.path)
  }

  private fun scpComponents(origin: String): Pair<String, String> {
    val match =
        SCP_ORIGIN.matchEntire(origin) ?: throw IllegalArgumentException("SSH origin 형식이 아닙니다.")
    return ownerAndRepository(match.groupValues[1])
  }

  private fun ownerAndRepository(path: String?): Pair<String, String> {
    val parts = path.orEmpty().split('/').filter(String::isNotEmpty)

    if (parts.size != 2) {
      throw IllegalArgumentException("origin 경로는 owner/repository 형식이어야 합니다.")
    }

    return parts[0] to parts[1]
  }

  private companion object {
    const val URI_SEPARATOR = "://"
    const val HTTPS_SCHEME = "https"
    const val SSH_SCHEME = "ssh"
    val SCP_ORIGIN = Regex("^(?:[^@/:\\s]+@)?[^/:\\s]+:(.+)$")
  }
}
