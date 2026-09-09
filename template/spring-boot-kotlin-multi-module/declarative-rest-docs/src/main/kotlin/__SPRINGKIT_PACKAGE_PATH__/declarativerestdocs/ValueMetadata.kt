package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import com.epages.restdocs.apispec.SimpleType
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.snippet.Attributes
import org.springframework.restdocs.snippet.Attributes.Attribute
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import kotlin.reflect.KClass

/** 하나의 [Sample]에서 해석한 문서 메타데이터입니다. */
data class ValueMetadata(
    val fieldType: Any,
    val simpleType: SimpleType?,
    val attributes: List<Attribute>,
)

/** 애플리케이션의 JSON 설정을 기준으로 [Sample]의 문서 메타데이터를 해석합니다. */
class ValueMetadataResolver(
    private val objectMapper: ObjectMapper,
) {
    fun resolve(sample: Sample): ValueMetadata {
        val classifier = sample.type.classifier as? KClass<*>
        if (classifier?.java?.isEnum == true) {
            return enumMetadata(classifier)
        }

        val (fieldType, simpleType) = primitiveTypeMapping[classifier]
            ?: (JsonFieldType.OBJECT to null)

        return ValueMetadata(
            fieldType = fieldType,
            simpleType = simpleType,
            attributes = emptyList(),
        )
    }

    private fun enumMetadata(enumType: KClass<*>): ValueMetadata {
        val enumValues =
            enumType.java.enumConstants.map { constant ->
                objectMapper.convertValue(constant, Any::class.java)
            }

        return ValueMetadata(
            fieldType = "enum",
            simpleType = SimpleType.STRING,
            attributes = listOf(Attributes.key("enumValues").value(enumValues)),
        )
    }

    private companion object {
        val primitiveTypeMapping: Map<KClass<*>, Pair<Any, SimpleType>> =
            mapOf(
                String::class to (JsonFieldType.STRING to SimpleType.STRING),
                Boolean::class to (JsonFieldType.BOOLEAN to SimpleType.BOOLEAN),
                Byte::class to (JsonFieldType.NUMBER to SimpleType.INTEGER),
                Short::class to (JsonFieldType.NUMBER to SimpleType.INTEGER),
                Int::class to (JsonFieldType.NUMBER to SimpleType.INTEGER),
                Long::class to (JsonFieldType.NUMBER to SimpleType.INTEGER),
                BigInteger::class to (JsonFieldType.NUMBER to SimpleType.INTEGER),
                Float::class to (JsonFieldType.NUMBER to SimpleType.NUMBER),
                Double::class to (JsonFieldType.NUMBER to SimpleType.NUMBER),
                BigDecimal::class to (JsonFieldType.NUMBER to SimpleType.NUMBER),
                LocalDate::class to ("date" to SimpleType.STRING),
            )
    }
}
