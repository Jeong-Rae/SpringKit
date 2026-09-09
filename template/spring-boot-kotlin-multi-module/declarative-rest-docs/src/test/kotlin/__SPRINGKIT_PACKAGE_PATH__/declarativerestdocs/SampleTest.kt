package __SPRINGKIT_PACKAGE_NAME__.declarativerestdocs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.reflect.typeOf

@OptIn(ExperimentalStdlibApi::class)
class SampleTest :
    FunSpec({
        context("sampleOf가 값을 보존하는 계약") {
            test("Long 값을 전달하면 값과 KType을 보존한다") {
                val sample = sampleOf(1L)

                sample.value shouldBe 1L
                sample.type shouldBe typeOf<Long>()
            }

            test("enum 값을 전달하면 값과 KType을 보존한다") {
                val sample = sampleOf(SampleUserRole.ADMIN)

                sample.value shouldBe SampleUserRole.ADMIN
                sample.type shouldBe typeOf<SampleUserRole>()
            }

            test("List<String> 값을 전달하면 원소의 KType을 보존한다") {
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
