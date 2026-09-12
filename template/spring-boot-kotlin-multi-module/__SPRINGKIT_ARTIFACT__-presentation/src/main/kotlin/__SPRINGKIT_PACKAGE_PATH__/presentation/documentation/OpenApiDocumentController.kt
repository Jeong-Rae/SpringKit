package __SPRINGKIT_PACKAGE_NAME__.presentation.documentation

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@Profile("local")
@RestController
class OpenApiDocumentController(
    @Value("\${springkit.openapi.document:classpath:/openapi/openapi3.json}")
    private val openApiDocument: Resource,
) {
  @GetMapping("/openapi3.json", produces = [MediaType.APPLICATION_JSON_VALUE])
  fun openApiDocument(): ResponseEntity<Resource> =
      ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(openApiDocument)
}
