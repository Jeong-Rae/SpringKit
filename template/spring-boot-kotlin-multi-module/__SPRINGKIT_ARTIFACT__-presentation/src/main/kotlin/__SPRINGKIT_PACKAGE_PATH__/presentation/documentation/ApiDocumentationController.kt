package __SPRINGKIT_PACKAGE_NAME__.presentation.documentation

import com.scalar.maven.core.ScalarHtmlRenderer
import java.util.Base64
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class ApiDocumentationController(
    @Value("\${springkit.openapi.document:classpath:/openapi/openapi3.json}")
    private val openApiDocument: Resource,
    private val environment: Environment,
) {
  @GetMapping("/api/docs", produces = [MediaType.TEXT_HTML_VALUE])
  fun apiDocumentation(): String {
    val encodedDocument =
        openApiDocument.inputStream.use { Base64.getEncoder().encodeToString(it.readAllBytes()) }
    val documentDownloadType =
        if (environment.acceptsProfiles(Profiles.of("local"))) "json" else "none"

    return """
      <!doctype html>
      <html>
        <head>
          <title>__SPRINGKIT_PROJECT_NAME__ API</title>
          <meta charset="utf-8" />
          <meta content="width=device-width, initial-scale=1" name="viewport" />
        </head>
        <body>
          <div id="app"></div>
          <script src="/api/docs/scalar.js"></script>
          <script>
            const bytes = Uint8Array.from(atob("$encodedDocument"), character => character.charCodeAt(0))
            const content = JSON.parse(new TextDecoder().decode(bytes))
            Scalar.createApiReference('#app', { content, documentDownloadType: '$documentDownloadType' })
          </script>
        </body>
      </html>
      """
        .trimIndent()
  }

  @GetMapping("/api/docs/scalar.js", produces = ["application/javascript"])
  fun scalarJavaScript(): ResponseEntity<ByteArray> =
      ResponseEntity.ok()
          .contentType(MediaType.parseMediaType("application/javascript"))
          .body(ScalarHtmlRenderer.getScalarJsContent())
}
