package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs.spring

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
class HttpFixtureController {

  @GetMapping("/api/members")
  fun members(
      @RequestParam page: Int,
      @RequestParam size: Int,
      @RequestParam status: String,
      @RequestParam tag: List<String>,
      @RequestHeader(HttpHeaders.AUTHORIZATION) authorization: String,
      @RequestHeader("X-Request-Id") requestId: String,
  ): MemberPageResponse {
    check(authorization == "Bearer test-token")
    check(requestId == "req-001")
    check(status == "ACTIVE")
    check(tag == listOf("spring", "java"))

    return MemberPageResponse(
        content = listOf(member(id = 1, name = "Jane", email = "jane@example.com", active = true)),
        page = page,
        size = size,
    )
  }

  @GetMapping("/api/members/{memberId}")
  fun member(
      @PathVariable memberId: Long,
      @CookieValue(name = "SESSION", required = false) session: String?,
  ): MemberResponse {
    if (session != null) {
      check(session == "session-token")
    }
    return member(id = memberId, name = "Jane", email = "jane@example.com", active = true)
  }

  @PostMapping("/api/members")
  fun createMember(
      @RequestHeader("Idempotency-Key") idempotencyKey: String,
      @RequestBody request: MemberWriteRequest,
  ): ResponseEntity<MemberResponse> {
    check(idempotencyKey == "create-member-001")
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(HttpHeaders.LOCATION, "/api/members/1")
        .body(request.toResponse(1))
  }

  @PutMapping("/api/members/{memberId}")
  fun replaceMember(
      @PathVariable memberId: Long,
      @RequestHeader(HttpHeaders.IF_MATCH) ifMatch: String,
      @RequestBody request: MemberWriteRequest,
  ): ResponseEntity<MemberResponse> {
    check(ifMatch == "\"member-1-v1\"")
    return ResponseEntity.ok()
        .header(HttpHeaders.ETAG, "\"member-$memberId-v2\"")
        .body(request.toResponse(memberId))
  }

  @PatchMapping(
      "/api/members/{memberId}",
      consumes = ["application/merge-patch+json"],
  )
  fun patchMember(
      @PathVariable memberId: Long,
      @RequestBody patch: Map<String, Any?>,
  ): MemberPatchResponse {
    check(patch["name"] == "Jane Smith")
    check(patch.containsKey("nickname"))
    check(patch["nickname"] == null)
    return MemberPatchResponse(id = memberId, name = "Jane Smith", nickname = null)
  }

  @DeleteMapping("/api/members/{memberId}")
  fun deleteMember(@PathVariable memberId: Long): ResponseEntity<Void> {
    check(memberId > 0)
    return ResponseEntity.noContent().build()
  }

  @PostMapping(
      "/api/sessions",
      consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE],
  )
  fun createSession(
      @RequestParam username: String,
      @RequestParam password: String,
      @RequestParam rememberMe: Boolean,
  ): ResponseEntity<SessionResponse> {
    check(username == "jane")
    check(password == "secret")

    val sessionCookie =
        ResponseCookie.from("SESSION", "session-token")
            .httpOnly(true)
            .path("/")
            .build()
            .toString()

    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, sessionCookie)
        .body(SessionResponse(memberId = 1, rememberMe = rememberMe))
  }

  @PostMapping(
      "/api/members/{memberId}/avatar",
      consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
  )
  fun uploadAvatar(
      @PathVariable memberId: Long,
      @RequestPart("metadata") metadata: Map<String, Any>,
      @RequestPart("file") file: MultipartFile,
  ): AvatarResponse {
    check(metadata["public"] == true)
    check(metadata["crop"] is Map<*, *>)
    check(file.originalFilename == "avatar.png")
    check(file.contentType == MediaType.IMAGE_PNG_VALUE)
    return AvatarResponse(memberId = memberId, filename = requireNotNull(file.originalFilename))
  }

  private fun member(
      id: Long,
      name: String,
      email: String,
      active: Boolean,
  ): MemberResponse =
      MemberResponse(
          id = id,
          name = name,
          email = email,
          active = active,
          profile = MemberProfile(age = 30, tags = listOf("java", "spring")),
      )
}

data class MemberPageResponse(
    val content: List<MemberResponse>,
    val page: Int,
    val size: Int,
)

data class MemberResponse(
    val id: Long,
    val name: String,
    val email: String,
    val active: Boolean,
    val profile: MemberProfile,
)

data class MemberWriteRequest(
    val name: String,
    val email: String,
    val active: Boolean,
    val profile: MemberProfile,
) {
  fun toResponse(id: Long): MemberResponse =
      MemberResponse(
          id = id,
          name = name,
          email = email,
          active = active,
          profile = profile,
      )
}

data class MemberProfile(
    val age: Int,
    val tags: List<String>,
)

data class MemberPatchResponse(
    val id: Long,
    val name: String,
    val nickname: String?,
)

data class SessionResponse(
    val memberId: Long,
    val rememberMe: Boolean,
)

data class AvatarResponse(
    val memberId: Long,
    val filename: String,
)
