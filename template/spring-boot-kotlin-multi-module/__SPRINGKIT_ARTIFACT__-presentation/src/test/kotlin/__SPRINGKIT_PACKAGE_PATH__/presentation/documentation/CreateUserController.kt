package __SPRINGKIT_PACKAGE_NAME__.presentation.documentation

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class CreateUserController {

  @PostMapping("/tenants/{tenantId}/users")
  fun createUser(
      @PathVariable tenantId: String,
      @RequestParam dryRun: Boolean,
      @RequestHeader("X-Request-Id") requestId: String,
      @RequestBody request: CreateUserRequest,
  ): ResponseEntity<CreateUserResponse> {
    check(!dryRun)
    check(request.name == "Alice")
    check(request.role == UserRole.ADMIN)

    return ResponseEntity.status(HttpStatus.CREATED)
        .header(HttpHeaders.LOCATION, "/tenants/$tenantId/users/user-123")
        .header("X-Request-Id", requestId)
        .body(CreateUserResponse(id = "user-123"))
  }
}

data class CreateUserRequest(
    val name: String,
    val role: UserRole,
)

data class CreateUserResponse(
    val id: String,
)

enum class UserRole {
  ADMIN
}
