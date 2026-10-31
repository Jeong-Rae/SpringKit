package com.springkit.agentguidebook.storage

import com.springkit.agentguidebook.errors.RepositoryIdentityUnavailableException
import com.springkit.agentguidebook.models.RepositoryIdentity
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path
import java.util.Locale
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UrlConfig

/**
 * Git 저장소의 origin 설정을 읽어 정규화된 소유자와 저장소 이름으로 변환합니다.
 *
 * JGit으로 현재 경로의 저장소를 탐색하고 origin의 첫 주소만 사용합니다.
 */
internal class RepositoryIdentityReader {

  /**
   * 지정한 작업 경로의 Git 저장소에서 origin 식별자를 읽습니다.
   *
   * 저장소, 설정 또는 주소를 확인할 수 없으면 원인을 보존한 예외를 전달합니다.
   */
  fun read(cwd: Path): RepositoryIdentity =
      try {
        val repository = FileRepositoryBuilder().setMustExist(true).findGitDir(cwd.toFile()).build()
        repository.use {
          val origin = readOrigin(it)
          val (owner, name) = originComponents(origin)
          RepositoryIdentity(owner, name.removeSuffix(".git"))
        }
      } catch (exception: IOException) {
        throw RepositoryIdentityUnavailableException("저장소 origin을 확인할 수 없습니다.", exception)
      } catch (exception: SecurityException) {
        throw RepositoryIdentityUnavailableException("저장소 origin을 확인할 수 없습니다.", exception)
      } catch (exception: URISyntaxException) {
        throw RepositoryIdentityUnavailableException("저장소 origin 주소를 정규화할 수 없습니다.", exception)
      } catch (exception: IllegalArgumentException) {
        throw RepositoryIdentityUnavailableException("저장소 origin 주소를 정규화할 수 없습니다.", exception)
      }

  private fun readOrigin(repository: Repository): String {
    val configuredOrigin =
        repository.config.getStringList("remote", "origin", "url").firstOrNull()
            ?: throw IllegalArgumentException("Git origin 조회가 실패했습니다: origin URL이 없습니다.")
    val rewrittenOrigin = UrlConfig(repository.config).replace(configuredOrigin).trim()
    require(rewrittenOrigin.isNotBlank()) { "저장소 origin 주소가 비어 있습니다." }

    return rewrittenOrigin
  }

  private fun originComponents(origin: String): Pair<String, String> {
    val isUri = origin.contains("://")

    if (isUri) {
      return uriComponents(origin)
    }

    return scpComponents(origin)
  }

  private fun uriComponents(origin: String): Pair<String, String> {
    val uri = URI(origin)
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val isSupportedScheme = scheme == "https" || scheme == "ssh"

    if (!isSupportedScheme) {
      throw IllegalArgumentException("HTTPS 또는 SSH origin만 지원합니다.")
    }

    val hasValidHost = !uri.host.isNullOrBlank()
    if (!hasValidHost) {
      throw IllegalArgumentException("origin 호스트가 비어 있습니다.")
    }

    val hasQueryOrFragment = uri.rawQuery != null || uri.rawFragment != null
    if (hasQueryOrFragment) {
      throw IllegalArgumentException("origin에 쿼리나 fragment를 지정할 수 없습니다.")
    }

    return ownerAndRepository(uri.path)
  }

  private fun scpComponents(origin: String): Pair<String, String> {
    val uriish = URIish(origin)
    val host = uriish.host.orEmpty()
    val path = uriish.path
    val hasUnsupportedAuthority = host.any { character ->
      character == '/' || character == ':' || character.isWhitespace()
    }
    val hasUnsupportedPassword = uriish.pass != null
    val hasWhitespace = origin.any(Char::isWhitespace)
    val isScpAddress = uriish.scheme == null && host.isNotBlank() && !path.isNullOrEmpty()
    val hasUnsupportedScpAddress =
        !isScpAddress || hasUnsupportedAuthority || hasUnsupportedPassword || hasWhitespace

    if (hasUnsupportedScpAddress) {
      throw IllegalArgumentException("SSH origin 형식이 아닙니다.")
    }

    return ownerAndRepository(path)
  }

  private fun ownerAndRepository(path: String?): Pair<String, String> {
    val parts = path.orEmpty().split('/').filter(String::isNotEmpty)
    val hasOwnerAndRepository = parts.size == 2

    if (!hasOwnerAndRepository) {
      throw IllegalArgumentException("origin 경로는 owner/repository 형식이어야 합니다.")
    }

    return parts[0] to parts[1]
  }
}
