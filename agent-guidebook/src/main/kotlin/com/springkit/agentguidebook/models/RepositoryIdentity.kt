package com.springkit.agentguidebook.models

/**
 * 정규화된 Git 저장소 소유자와 저장소 이름을 함께 보유합니다.
 *
 * @property owner 공백과 `/`가 없는 소유자 이름
 * @property repository 공백과 `/`가 없는 저장소 이름
 * @throws IllegalArgumentException 구성값이 비어 있거나 공백 또는 `/`를 포함하는 경우
 */
data class RepositoryIdentity(
    val owner: String,
    val repository: String,
) {
  init {
    require(owner.isNotBlank()) { "저장소 소유자는 비어 있거나 공백으로만 구성될 수 없습니다." }
    require(repository.isNotBlank()) { "저장소 이름은 비어 있거나 공백으로만 구성될 수 없습니다." }
    require(owner.none(Char::isWhitespace)) { "저장소 소유자에 공백 문자를 넣을 수 없습니다." }
    require(repository.none(Char::isWhitespace)) { "저장소 이름에 공백 문자를 넣을 수 없습니다." }
    require('/' !in owner) { "저장소 소유자에 슬래시를 넣을 수 없습니다." }
    require('/' !in repository) { "저장소 이름에 슬래시를 넣을 수 없습니다." }
  }

  /**
   * 저장소 식별자를 `owner/repository` 형식으로 반환합니다.
   *
   * 두 구성값의 문자열 표현은 변경하지 않습니다.
   *
   * @return owner와 repository를 슬래시로 연결한 식별자
   */
  fun qualifiedName(): String = "$owner/$repository"
}
