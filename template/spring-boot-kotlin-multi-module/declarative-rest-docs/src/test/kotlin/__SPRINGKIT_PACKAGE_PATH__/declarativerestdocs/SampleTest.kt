@file:OptIn(ExperimentalStdlibApi::class)

package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import kotlin.reflect.KType
import kotlin.reflect.typeOf

class SampleTest :
    FunSpec({
        context("sampleOf가 원시 타입을 보존하는 계약") {
            withData(
                nameFn = { it.testName },
                primitiveSampleCases(),
            ) { case ->
                case.sample.value shouldBe case.expectedValue
                case.sample.type shouldBe case.expectedType
            }
        }

        context("sampleOf가 enum 타입을 보존하는 계약") {
            test("enum 값을 입력하면 값과 KType을 보존한다") {
                val sample = sampleOf(SampleUserRole.ADMIN)

                sample.value shouldBe SampleUserRole.ADMIN
                sample.type shouldBe typeOf<SampleUserRole>()
            }
        }

        context("sampleOf가 컬렉션 타입을 보존하는 계약") {
            withData(
                nameFn = { it.testName },
                collectionSampleCases(),
            ) { case ->
                case.sample.value shouldBe case.expectedValue
                case.sample.type shouldBe case.expectedType
            }
        }
    })

private fun primitiveSampleCases(): List<SampleContractCase> =
    listOf(
        sampleCase("String", sampleOf("value"), "value", typeOf<String>()),
        booleanSampleCase(true),
        booleanSampleCase(false),
        sampleCase("Byte", sampleOf(1.toByte()), 1.toByte(), typeOf<Byte>()),
        sampleCase("Short", sampleOf(1.toShort()), 1.toShort(), typeOf<Short>()),
        sampleCase("Int", sampleOf(1), 1, typeOf<Int>()),
        sampleCase("Long", sampleOf(1L), 1L, typeOf<Long>()),
        sampleCase("BigInteger", sampleOf<BigInteger>(BigInteger.ONE), BigInteger.ONE, typeOf<BigInteger>()),
        sampleCase("Float", sampleOf(1.5F), 1.5F, typeOf<Float>()),
        sampleCase("Double", sampleOf(1.5), 1.5, typeOf<Double>()),
        sampleCase("BigDecimal", sampleOf(BigDecimal("1.5")), BigDecimal("1.5"), typeOf<BigDecimal>()),
        sampleCase(
            typeName = "LocalDate",
            sample = sampleOf<LocalDate>(LocalDate.of(2026, 9, 9)),
            expectedValue = LocalDate.of(2026, 9, 9),
            expectedType = typeOf<LocalDate>(),
        ),
    )

private fun collectionSampleCases(): List<SampleContractCase> =
    listOf(
        sampleCase(
            typeName = "List<String>",
            sample = sampleOf(listOf("USER", "ADMIN")),
            expectedValue = listOf("USER", "ADMIN"),
            expectedType = typeOf<List<String>>(),
        ),
        sampleCase(
            typeName = "List<Int>",
            sample = sampleOf(listOf(1, 2)),
            expectedValue = listOf(1, 2),
            expectedType = typeOf<List<Int>>(),
        ),
        sampleCase(
            typeName = "Set<String>",
            sample = sampleOf(setOf("USER", "ADMIN")),
            expectedValue = setOf("USER", "ADMIN"),
            expectedType = typeOf<Set<String>>(),
        ),
        sampleCase(
            typeName = "Collection<String>",
            sample = sampleOf<Collection<String>>(listOf("USER", "ADMIN")),
            expectedValue = listOf("USER", "ADMIN"),
            expectedType = typeOf<Collection<String>>(),
        ),
        sampleCase(
            typeName = "Array<String>",
            sample = sampleOf(arrayOf("USER", "ADMIN")),
            expectedValue = arrayOf("USER", "ADMIN"),
            expectedType = typeOf<Array<String>>(),
        ),
    )

private fun sampleCase(
    typeName: String,
    sample: Sample,
    expectedValue: Any,
    expectedType: KType,
): SampleContractCase =
    SampleContractCase(
        testName = "$typeName 값을 입력하면 값과 KType을 보존한다",
        sample = sample,
        expectedValue = expectedValue,
        expectedType = expectedType,
    )

private fun booleanSampleCase(value: Boolean): SampleContractCase =
    SampleContractCase(
        testName = "${value}를 Boolean 값으로 입력하면 값과 KType을 보존한다",
        sample = sampleOf(value),
        expectedValue = value,
        expectedType = typeOf<Boolean>(),
    )

private data class SampleContractCase(
    val testName: String,
    val sample: Sample,
    val expectedValue: Any,
    val expectedType: KType,
)

private enum class SampleUserRole {
    USER,
    ADMIN,
}
