package __SPRINGKIT_PACKAGE_NAME__.application.greeting

import __SPRINGKIT_PACKAGE_NAME__.domain.greeting.Greeting
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GreetingServiceTests :
    FunSpec({
      context("GreetingService의 인사말 조회") {
        test("포트가 인사말을 반환하면, 같은 메시지를 제공한다") {
          val service = GreetingService { Greeting("Hello, Springkit!") }

          service.greeting().message shouldBe "Hello, Springkit!"
        }
      }
    })
