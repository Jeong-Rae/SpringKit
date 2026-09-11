@file:OptIn(ExperimentalStdlibApi::class)

package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import com.epages.restdocs.apispec.SimpleType
import com.fasterxml.jackson.annotation.JsonValue
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import org.springframework.restdocs.payload.JsonFieldType
import tools.jackson.databind.ObjectMapper

class ValueMetadataResolverTest :
    FunSpec({
      val resolver = ValueMetadataResolver(ObjectMapper())

      context("원시값과 날짜 타입의 메타데이터 해석") {
        withData(
            nameFn = { it.name },
            primitiveMetadataCases(),
        ) { case ->
          val metadata = resolver.resolve(case.sample)

          metadata.fieldType shouldBe case.fieldType
          metadata.simpleType shouldBe case.simpleType
          metadata.attributes.shouldBeEmpty()
        }
      }

      context("Jackson enum의 메타데이터 해석") {
        test("기본 enum을 해석하면, 전체 상수명을 enumValues로 제공한다") {
          val metadata = resolver.resolve(sampleOf(BasicRole.ADMIN))

          metadata.fieldType shouldBe "enum"
          metadata.simpleType shouldBe SimpleType.STRING
          metadata.attributes.single().key shouldBe "enumValues"
          metadata.attributes.single().value shouldBe listOf("USER", "ADMIN")
        }

        test("JsonValue enum을 해석하면, 전체 직렬화 값을 enumValues로 제공한다") {
          val metadata = resolver.resolve(sampleOf(SerializedRole.ADMIN))

          metadata.fieldType shouldBe "enum"
          metadata.simpleType shouldBe SimpleType.STRING
          metadata.attributes.single().key shouldBe "enumValues"
          metadata.attributes.single().value shouldBe listOf("user", "admin")
        }
      }

      context("배열과 컬렉션의 메타데이터 해석") {
        withData(
            nameFn = { it.name },
            arrayMetadataCases(),
        ) { case ->
          val metadata = resolver.resolve(case.sample)

          metadata.fieldType shouldBe JsonFieldType.ARRAY
          metadata.simpleType shouldBe case.simpleType
          metadata.attributeValues() shouldBe case.attributes
        }

        test("enum 컬렉션을 해석하면, 원소의 Jackson 직렬화 값을 보존한다") {
          val metadata = resolver.resolve(sampleOf<List<SerializedRole>>(emptyList()))

          metadata.attributeValues() shouldBe
              listOf(
                  "itemsType" to "ENUM",
                  "enumValues" to listOf("user", "admin"),
              )
        }

        test("동일한 Sample을 반복해서 해석하면, 같은 메타데이터를 생성한다") {
          val sample = sampleOf(listOf("USER", "ADMIN"))

          val first = resolver.resolve(sample)
          val second = resolver.resolve(sample)

          first.fieldType shouldBe second.fieldType
          first.simpleType shouldBe second.simpleType
          first.attributeValues() shouldBe second.attributeValues()
        }
      }
    })

private fun primitiveMetadataCases(): List<PrimitiveMetadataCase> =
    listOf(
        primitiveMetadataCase("String", sampleOf("value"), JsonFieldType.STRING, SimpleType.STRING),
        primitiveMetadataCase("Boolean", sampleOf(true), JsonFieldType.BOOLEAN, SimpleType.BOOLEAN),
        primitiveMetadataCase(
            "Byte",
            sampleOf(1.toByte()),
            JsonFieldType.NUMBER,
            SimpleType.INTEGER,
        ),
        primitiveMetadataCase(
            "Short",
            sampleOf(1.toShort()),
            JsonFieldType.NUMBER,
            SimpleType.INTEGER,
        ),
        primitiveMetadataCase("Int", sampleOf(1), JsonFieldType.NUMBER, SimpleType.INTEGER),
        primitiveMetadataCase("Long", sampleOf(1L), JsonFieldType.NUMBER, SimpleType.INTEGER),
        primitiveMetadataCase(
            "BigInteger",
            sampleOf(BigInteger.ONE),
            JsonFieldType.NUMBER,
            SimpleType.INTEGER,
        ),
        primitiveMetadataCase("Float", sampleOf(1.5F), JsonFieldType.NUMBER, SimpleType.NUMBER),
        primitiveMetadataCase("Double", sampleOf(1.5), JsonFieldType.NUMBER, SimpleType.NUMBER),
        primitiveMetadataCase(
            "BigDecimal",
            sampleOf(BigDecimal("1.5")),
            JsonFieldType.NUMBER,
            SimpleType.NUMBER,
        ),
        primitiveMetadataCase(
            "LocalDate",
            sampleOf(LocalDate.of(2026, 9, 9)),
            "date",
            SimpleType.STRING,
        ),
    )

private fun primitiveMetadataCase(
    typeName: String,
    sample: Sample,
    fieldType: Any,
    simpleType: SimpleType,
): PrimitiveMetadataCase =
    PrimitiveMetadataCase(
        name = "$typeName 값을 해석하면, 정의된 메타데이터를 제공한다",
        sample = sample,
        fieldType = fieldType,
        simpleType = simpleType,
    )

private data class PrimitiveMetadataCase(
    val name: String,
    val sample: Sample,
    val fieldType: Any,
    val simpleType: SimpleType,
)

private fun arrayMetadataCases(): List<ArrayMetadataCase> =
    listOf(
        ArrayMetadataCase(
            name = "빈 List<String>을 해석하면, 선언된 원소 타입을 제공한다",
            sample = sampleOf<List<String>>(emptyList()),
            simpleType = SimpleType.STRING,
            attributes = listOf("itemsType" to "STRING"),
        ),
        ArrayMetadataCase(
            name = "Set<Int>를 해석하면, 정수 원소 타입을 제공한다",
            sample = sampleOf(setOf(1, 2)),
            simpleType = SimpleType.INTEGER,
            attributes = listOf("itemsType" to "NUMBER"),
        ),
        ArrayMetadataCase(
            name = "Array<String>을 해석하면, 문자열 원소 타입을 제공한다",
            sample = sampleOf(arrayOf("USER", "ADMIN")),
            simpleType = SimpleType.STRING,
            attributes = listOf("itemsType" to "STRING"),
        ),
        ArrayMetadataCase(
            name = "IntArray를 해석하면, 정수 원소 타입을 제공한다",
            sample = sampleOf(intArrayOf(1, 2)),
            simpleType = SimpleType.INTEGER,
            attributes = listOf("itemsType" to "NUMBER"),
        ),
    )

private fun ValueMetadata.attributeValues(): List<Pair<String, Any>> = attributes.map { attribute ->
  attribute.key to attribute.value
}

private data class ArrayMetadataCase(
    val name: String,
    val sample: Sample,
    val simpleType: SimpleType?,
    val attributes: List<Pair<String, Any>>,
)

private enum class BasicRole {
  USER,
  ADMIN,
}

private enum class SerializedRole(@get:JsonValue val serializedValue: String) {
  USER("user"),
  ADMIN("admin"),
}
