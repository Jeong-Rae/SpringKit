package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.reflect.typeOf

@OptIn(ExperimentalStdlibApi::class)
class SampleTest :
    FunSpec({
        context("sampleOf 계약") {
            test("primitive의 값과 타입을 보존한다") {
                val sample = sampleOf(1L)

                sample.value shouldBe 1L
                sample.type shouldBe typeOf<Long>()
            }

            test("enum의 값과 타입을 보존한다") {
                val sample = sampleOf(SampleUserRole.ADMIN)

                sample.value shouldBe SampleUserRole.ADMIN
                sample.type shouldBe typeOf<SampleUserRole>()
            }

            test("generic collection의 원소 타입을 보존한다") {
                val sample = sampleOf(listOf("USER", "ADMIN"))

                sample.value shouldBe listOf("USER", "ADMIN")
                sample.type shouldBe typeOf<List<String>>()
            }
        }
    })

private enum class SampleUserRole {
    USER,
    ADMIN,
}
