package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import com.epages.restdocs.apispec.SimpleType
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import kotlin.reflect.KClass
import kotlin.reflect.KType
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.snippet.Attributes
import org.springframework.restdocs.snippet.Attributes.Attribute
import tools.jackson.databind.ObjectMapper

/** 하나의 [Sample]에서 해석한 문서 metadata */
data class ValueMetadata(
    val fieldType: Any,
    val simpleType: SimpleType?,
    val attributes: List<Attribute>,
)

/** 애플리케이션의 JSON 설정을 기준으로 한 [Sample] 문서 metadata 해석 */
class ValueMetadataResolver(private val objectMapper: ObjectMapper) {
  fun resolve(sample: Sample): ValueMetadata = resolveType(sample.type)

  private fun resolveType(type: KType): ValueMetadata {
    val classifier = type.classifier as? KClass<*>
    if (classifier != null && classifier.isArrayOrCollection()) {
      return arrayMetadata(type, classifier)
    }

    if (classifier?.java?.isEnum == true) {
      return enumMetadata(classifier)
    }

    val (fieldType, simpleType) = primitiveTypeMapping[classifier] ?: (JsonFieldType.OBJECT to null)

    return ValueMetadata(
        fieldType = fieldType,
        simpleType = simpleType,
        attributes = emptyList(),
    )
  }

  private fun arrayMetadata(
      type: KType,
      containerType: KClass<*>,
  ): ValueMetadata {
    val itemType = type.arguments.singleOrNull()?.type
    val itemClassifier =
        itemType?.classifier as? KClass<*> ?: containerType.java.componentType?.kotlin
    val itemMetadata = itemType?.let(::resolveType) ?: itemClassifier?.let(::scalarMetadata)
    val attributes =
        if (itemMetadata == null) {
          emptyList()
        } else {
          listOf(Attributes.key("itemsType").value(itemMetadata.fieldType.attributeValue())) +
              itemMetadata.attributes
        }

    return ValueMetadata(
        fieldType = JsonFieldType.ARRAY,
        simpleType = itemMetadata?.simpleType,
        attributes = attributes,
    )
  }

  private fun scalarMetadata(classifier: KClass<*>): ValueMetadata {
    if (classifier.java.isEnum) {
      return enumMetadata(classifier)
    }

    val (fieldType, simpleType) = primitiveTypeMapping[classifier] ?: (JsonFieldType.OBJECT to null)
    return ValueMetadata(fieldType, simpleType, emptyList())
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

    fun KClass<*>.isArrayOrCollection(): Boolean =
        java.isArray || Collection::class.java.isAssignableFrom(java)

    fun Any.attributeValue(): String =
        when (this) {
          is JsonFieldType -> name
          else -> toString().uppercase()
        }
  }
}
