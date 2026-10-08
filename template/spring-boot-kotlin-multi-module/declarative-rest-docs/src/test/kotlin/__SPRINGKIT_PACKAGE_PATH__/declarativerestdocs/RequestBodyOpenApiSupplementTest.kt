package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

class RequestBodyOpenApiSupplementTest :
    FunSpec({
      test("raw body는 field property 없이 최상위 binary schema와 전체 설명을 만듭니다") {
        val source =
            mapOf(
                "openapi" to "3.0.1",
                "paths" to
                    mapOf(
                        "/images" to
                            mapOf(
                                "post" to
                                    mapOf(
                                        "operationId" to "upload-image",
                                        "responses" to mapOf("204" to mapOf("description" to "성공")),
                                    )
                            )
                    ),
            )

        val result =
            RequestBodyOpenApiSupplement.apply(
                source,
                listOf(
                    RequestBodyOpenApiMetadata(
                        operationId = "upload-image",
                        kind = "RAW",
                        contentType = "application/octet-stream",
                        description = "이미지 원본 바이트",
                        binaryParts = emptyList(),
                    )
                ),
            )

        val body = operation(result, "upload-image")["requestBody"] as Map<*, *>
        body["required"] shouldBe true
        body["description"] shouldBe "이미지 원본 바이트"
        val media = body["content"] as Map<*, *>
        val schema = (media["application/octet-stream"] as Map<*, *>)["schema"] as Map<*, *>
        schema shouldBe mapOf("type" to "string", "format" to "binary")
      }

      test("optional raw body를 선택적 requestBody로 표현합니다") {
        val result =
            RequestBodyOpenApiSupplement.apply(
                mapOf(
                    "openapi" to "3.0.1",
                    "paths" to
                        mapOf(
                            "/images" to
                                mapOf(
                                    "post" to
                                        mapOf(
                                            "operationId" to "upload-optional-image",
                                            "responses" to
                                                mapOf("204" to mapOf("description" to "성공")),
                                        )
                                )
                        ),
                ),
                listOf(
                    RequestBodyOpenApiMetadata(
                        operationId = "upload-optional-image",
                        kind = "RAW",
                        contentType = "application/octet-stream",
                        description = "선택적 이미지 원본 바이트",
                        optional = true,
                        binaryParts = emptyList(),
                    )
                ),
            )

        val body = operation(result, "upload-optional-image")["requestBody"] as Map<*, *>
        body["required"] shouldBe false
      }

      test("multipart part 이름을 그대로 유지하고 ByteArray만 binary로 보완합니다") {
        val source =
            mapOf(
                "openapi" to "3.0.1",
                "paths" to
                    mapOf(
                        "/images" to
                            mapOf(
                                "post" to
                                    mapOf(
                                        "operationId" to "upload-image",
                                        "requestBody" to
                                            mapOf(
                                                "content" to
                                                    mapOf(
                                                        "multipart/form-data" to
                                                            mapOf(
                                                                "schema" to
                                                                    mapOf(
                                                                        "type" to "object",
                                                                        "required" to
                                                                            listOf(
                                                                                "file",
                                                                                "caption",
                                                                            ),
                                                                        "properties" to
                                                                            mapOf(
                                                                                "file" to
                                                                                    mapOf(
                                                                                        "type" to
                                                                                            "string",
                                                                                        "description" to
                                                                                            "이미지 파일",
                                                                                    ),
                                                                                "caption" to
                                                                                    mapOf(
                                                                                        "type" to
                                                                                            "string",
                                                                                        "description" to
                                                                                            "설명",
                                                                                    ),
                                                                                "count" to
                                                                                    mapOf(
                                                                                        "type" to
                                                                                            "integer"
                                                                                    ),
                                                                                "metadata.name" to
                                                                                    mapOf(
                                                                                        "type" to
                                                                                            "string"
                                                                                    ),
                                                                            ),
                                                                    )
                                                            )
                                                    )
                                            ),
                                    )
                            )
                    ),
            )

        val result =
            RequestBodyOpenApiSupplement.apply(
                source,
                listOf(
                    RequestBodyOpenApiMetadata(
                        operationId = "upload-image",
                        kind = "MULTIPART",
                        contentType = "multipart/form-data",
                        description = null,
                        binaryParts = listOf("file"),
                        resourceFields =
                            listOf(
                                RequestBodyOpenApiField(
                                    path = "file",
                                    description = "이미지 파일",
                                    type = "STRING",
                                    contentType = "application/octet-stream",
                                    optional = false,
                                    attributes = emptyMap(),
                                ),
                                RequestBodyOpenApiField(
                                    path = "caption",
                                    description = "설명",
                                    type = "STRING",
                                    contentType = "text/plain;charset=UTF-8",
                                    optional = false,
                                    attributes = emptyMap(),
                                ),
                                RequestBodyOpenApiField(
                                    path = "count",
                                    description = "개수",
                                    type = "INTEGER",
                                    contentType = "application/json",
                                    optional = false,
                                    attributes = emptyMap(),
                                ),
                                RequestBodyOpenApiField(
                                    path = "metadata.name",
                                    description = "메타데이터 part",
                                    type = "STRING",
                                    contentType = "application/json",
                                    optional = false,
                                    attributes = emptyMap(),
                                ),
                            ),
                    )
                ),
            )
        val body = operation(result, "upload-image")["requestBody"] as Map<*, *>
        val media = body["content"] as Map<*, *>
        val schema = (media["multipart/form-data"] as Map<*, *>)["schema"] as Map<*, *>
        val properties = schema["properties"] as Map<*, *>

        schema["required"] shouldBe listOf("file", "caption", "count", "metadata.name")
        properties["file"] shouldBe
            mapOf("type" to "string", "format" to "binary", "description" to "이미지 파일")
        properties["caption"] shouldBe mapOf("type" to "string", "description" to "설명")
        properties["count"] shouldBe mapOf("type" to "integer", "description" to "개수")
        properties["metadata.name"] shouldBe
            mapOf("type" to "string", "description" to "메타데이터 part")
        properties.containsKey("metadata") shouldBe false
        val encoding = ((media["multipart/form-data"] as Map<*, *>)["encoding"] as Map<*, *>)
        (encoding["file"] as Map<*, *>)["contentType"] shouldBe "application/octet-stream"
        (encoding["caption"] as Map<*, *>)["contentType"] shouldBe "text/plain;charset=UTF-8"
        (encoding["count"] as Map<*, *>)["contentType"] shouldBe "application/json"
        (encoding["metadata.name"] as Map<*, *>)["contentType"] shouldBe "application/json"
      }

      test("문서에서 제외한 multipart part만 있으면 빈 object schema를 생성합니다") {
        val source =
            mapOf(
                "paths" to
                    mapOf(
                        "/upload" to
                            mapOf(
                                "post" to
                                    mapOf(
                                        "operationId" to "upload-empty",
                                        "requestBody" to
                                            mapOf(
                                                "content" to
                                                    mapOf(
                                                        "multipart/form-data" to
                                                            mapOf("schema" to null)
                                                    )
                                            ),
                                    )
                            )
                    ),
            )

        val result =
            RequestBodyOpenApiSupplement.apply(
                source,
                listOf(
                    RequestBodyOpenApiMetadata(
                        operationId = "upload-empty",
                        kind = "MULTIPART",
                        contentType = "multipart/form-data",
                        description = null,
                        binaryParts = emptyList(),
                    )
                ),
            )
        val body = operation(result, "upload-empty")["requestBody"] as Map<*, *>
        val media = (body["content"] as Map<*, *>)["multipart/form-data"] as Map<*, *>
        val schema = media["schema"] as Map<*, *>

        schema["type"] shouldBe "object"
        schema["properties"] shouldBe emptyMap<String, Any?>()
        schema["required"] shouldBe emptyList<String>()
      }
    })

private fun operation(document: Map<String, Any?>, operationId: String): Map<*, *> =
    (document["paths"] as Map<*, *>)
        .values
        .asSequence()
        .filterIsInstance<Map<*, *>>()
        .flatMap { it.values.asSequence() }
        .filterIsInstance<Map<*, *>>()
        .single { it["operationId"] == operationId }

/** Explicit Gradle JavaExec entry point; tests themselves never rewrite generated artifacts. */
fun main(args: Array<String>) {
  require(args.size == 5) {
    "사용법: <snippets-dir> <generated-json> <generated-yaml> <output-json> <output-yaml>"
  }
  val snippetsDirectory = java.nio.file.Path.of(args[0])
  val dumperOptions =
      DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        isPrettyFlow = true
        indent = 2
      }
  val yaml = Yaml(dumperOptions)
  val mapper = tools.jackson.databind.ObjectMapper()
  val metadata =
      java.nio.file.Files.walk(snippetsDirectory).use { paths ->
        paths
            .filter {
              java.nio.file.Files.isRegularFile(it) &&
                  it.fileName.toString() == "request-body-metadata.json"
            }
            .map { path ->
              @Suppress("UNCHECKED_CAST")
              val value = yaml.load<Map<String, Any?>>(java.nio.file.Files.readString(path))
              RequestBodyOpenApiMetadata(
                  operationId = value.requiredString("operationId"),
                  kind = value.requiredString("kind"),
                  contentType = value["contentType"] as? String,
                  description = value["description"] as? String,
                  optional = value["optional"] as? Boolean ?: false,
                  binaryParts =
                      (value["binaryParts"] as? List<*>)?.map { it as String } ?: emptyList(),
                  resourceFields =
                      (value["resourceFields"] as? List<*>)?.map { item ->
                        @Suppress("UNCHECKED_CAST") val field = item as Map<String, Any?>
                        @Suppress("UNCHECKED_CAST")
                        RequestBodyOpenApiField(
                            path = field.requiredString("path"),
                            description = field.requiredString("description"),
                            type = field.requiredString("type"),
                            contentType = field.requiredString("contentType"),
                            optional = field["optional"] as? Boolean ?: false,
                            attributes = field["attributes"] as? Map<String, Any?> ?: emptyMap(),
                        )
                      } ?: emptyList(),
              )
            }
            .toList()
      }
  val jsonInput =
      yaml.load<Map<String, Any?>>(java.nio.file.Files.readString(java.nio.file.Path.of(args[1])))
  val yamlInput =
      yaml.load<Map<String, Any?>>(java.nio.file.Files.readString(java.nio.file.Path.of(args[2])))
  val jsonOutput = RequestBodyOpenApiSupplement.apply(jsonInput, metadata)
  val yamlOutput = RequestBodyOpenApiSupplement.apply(yamlInput, metadata)
  check(jsonOutput == yamlOutput) { "생성된 JSON과 YAML OpenAPI 의미가 다릅니다." }

  val jsonPath = java.nio.file.Path.of(args[3])
  val yamlPath = java.nio.file.Path.of(args[4])
  java.nio.file.Files.createDirectories(jsonPath.parent)
  java.nio.file.Files.createDirectories(yamlPath.parent)
  mapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), jsonOutput)
  java.nio.file.Files.writeString(yamlPath, yaml.dump(yamlOutput))
}

private fun Map<String, Any?>.requiredString(key: String): String =
    this[key] as? String ?: error("request body metadata의 $key 값이 없습니다.")
