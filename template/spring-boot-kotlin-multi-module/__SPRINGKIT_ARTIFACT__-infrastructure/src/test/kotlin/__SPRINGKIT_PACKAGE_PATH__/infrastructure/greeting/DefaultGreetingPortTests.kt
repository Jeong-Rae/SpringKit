package __SPRINGKIT_PACKAGE_NAME__.infrastructure.greeting

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DefaultGreetingPortTests :
    FunSpec({
      context("DefaultGreetingPort의 인사말 조회") {
        test("인사말을 조회하면, 기본 메시지를 제공한다") {
          val greetingPort = DefaultGreetingPort()

          greetingPort.load().message shouldBe "Hello, Springkit!"
        }
      }
    })
