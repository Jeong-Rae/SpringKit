package __SPRINGKIT_PACKAGE_NAME__.domain.greeting

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GreetingTests :
    FunSpec({
        context("Greeting의 메시지") {
            test("인사말을 생성하면, 입력한 메시지를 보존한다") {
                val greeting = Greeting("Hello, Springkit!")

                greeting.message shouldBe "Hello, Springkit!"
            }
        }
    })
