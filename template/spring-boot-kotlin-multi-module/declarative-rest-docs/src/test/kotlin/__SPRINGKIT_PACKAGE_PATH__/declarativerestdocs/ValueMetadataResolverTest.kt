@file:OptIn(ExperimentalStdlibApi::class)

package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import com.fasterxml.jackson.annotation.JsonValue
import com.epages.restdocs.apispec.SimpleType
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.springframework.restdocs.payload.JsonFieldType
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate

class ValueMetadataResolverTest :
    FunSpec({
        val resolver = ValueMetadataResolver(ObjectMapper())

        context("원시값과 날짜 타입 메타데이터 계약") {
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

        context("Jackson enum 메타데이터 계약") {
            test("기본 enum은 전체 상수명을 enumValues로 제공한다") {
                val metadata = resolver.resolve(sampleOf(BasicRole.ADMIN))

                metadata.fieldType shouldBe "enum"
                metadata.simpleType shouldBe SimpleType.STRING
                metadata.attributes.single().key shouldBe "enumValues"
                metadata.attributes.single().value shouldBe listOf("USER", "ADMIN")
            }

            test("JsonValue enum은 전체 직렬화 값을 enumValues로 제공한다") {
                val metadata = resolver.resolve(sampleOf(SerializedRole.ADMIN))

                metadata.fieldType shouldBe "enum"
                metadata.simpleType shouldBe SimpleType.STRING
                metadata.attributes.single().key shouldBe "enumValues"
                metadata.attributes.single().value shouldBe listOf("user", "admin")
            }
        }
    })

private fun primitiveMetadataCases(): List<PrimitiveMetadataCase> =
    listOf(
        PrimitiveMetadataCase("String", sampleOf("value"), JsonFieldType.STRING, SimpleType.STRING),
        PrimitiveMetadataCase("Boolean", sampleOf(true), JsonFieldType.BOOLEAN, SimpleType.BOOLEAN),
        PrimitiveMetadataCase("Byte", sampleOf(1.toByte()), JsonFieldType.NUMBER, SimpleType.INTEGER),
        PrimitiveMetadataCase("Short", sampleOf(1.toShort()), JsonFieldType.NUMBER, SimpleType.INTEGER),
        PrimitiveMetadataCase("Int", sampleOf(1), JsonFieldType.NUMBER, SimpleType.INTEGER),
        PrimitiveMetadataCase("Long", sampleOf(1L), JsonFieldType.NUMBER, SimpleType.INTEGER),
        PrimitiveMetadataCase("BigInteger", sampleOf(BigInteger.ONE), JsonFieldType.NUMBER, SimpleType.INTEGER),
        PrimitiveMetadataCase("Float", sampleOf(1.5F), JsonFieldType.NUMBER, SimpleType.NUMBER),
        PrimitiveMetadataCase("Double", sampleOf(1.5), JsonFieldType.NUMBER, SimpleType.NUMBER),
        PrimitiveMetadataCase("BigDecimal", sampleOf(BigDecimal("1.5")), JsonFieldType.NUMBER, SimpleType.NUMBER),
        PrimitiveMetadataCase("LocalDate", sampleOf(LocalDate.of(2026, 9, 9)), "date", SimpleType.STRING),
    )

private data class PrimitiveMetadataCase(
    val name: String,
    val sample: Sample,
    val fieldType: Any,
    val simpleType: SimpleType,
)

private enum class BasicRole {
    USER,
    ADMIN,
}

private enum class SerializedRole(
    @get:JsonValue val serializedValue: String,
) {
    USER("user"),
    ADMIN("admin"),
}
