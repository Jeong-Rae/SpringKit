package io.springkit.workflow.adapter.json

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@kotlinx.serialization.Serializable private data class KnownJsonValue(val value: String)

@kotlinx.serialization.Serializable
private data class NamedJsonValue(val reviewRevision: String, val changeRevision: String)

@OptIn(ExperimentalSerializationApi::class)
class JsonContractTest :
    FunSpec({
      context("kotlinx Json의 원시값과 출력 형식을 확인할 때") {
        test("JsonObject에 원시값을 입력하면, 값을 보존한 compact JSON을 생성합니다") {
          val value = buildJsonObject {
            put("name", JsonPrimitive("workflow"))
            put("enabled", true)
            put("count", 2)
          }

          value["name"]?.jsonPrimitive?.content shouldBe "workflow"
          value.toString() shouldBe "{\"name\":\"workflow\",\"enabled\":true,\"count\":2}"
        }
      }

      context("WorkflowJson의 설정을 확인할 때") {
        test("알 수 없는 키를 입력하면, JSON 역직렬화를 거부합니다") {
          shouldThrow<kotlinx.serialization.json.JsonDecodingException> {
            WorkflowJson.format.decodeFromString<KnownJsonValue>(
                "{\"value\":\"ok\",\"extra\":true}"
            )
          }
        }

        test("기본값을 포함한 값을 인코딩하면, compact JSON을 생성합니다") {
          WorkflowJson.format.encodeToString(KnownJsonValue("ok")) shouldBe "{\"value\":\"ok\"}"
        }

        test("Kotlin 프로퍼티 이름을 인코딩하면, 명세의 snake_case 필드 이름을 사용합니다") {
          WorkflowJson.format.encodeToString(NamedJsonValue("rv-1", "cr-1")) shouldBe
              "{\"review_revision\":\"rv-1\",\"change_revision\":\"cr-1\"}"
        }

        test("WorkflowJson 설정을 조회하면, 계약에 맞는 옵션을 반환합니다") {
          val configuration = WorkflowJson.format.configuration

          configuration.explicitNulls shouldBe false
          configuration.encodeDefaults shouldBe true
          configuration.ignoreUnknownKeys shouldBe false
          configuration.classDiscriminator shouldBe "type"
          configuration.prettyPrint shouldBe false
        }
      }

      context("JSON parser의 입력을 확인할 때") {
        test("잘못된 JSON을 입력하면, JSONDecodingException을 발생시킵니다") {
          shouldThrow<kotlinx.serialization.json.JsonDecodingException> {
            Json.parseToJsonElement("{\"value\":}")
          }
        }
      }
    })
