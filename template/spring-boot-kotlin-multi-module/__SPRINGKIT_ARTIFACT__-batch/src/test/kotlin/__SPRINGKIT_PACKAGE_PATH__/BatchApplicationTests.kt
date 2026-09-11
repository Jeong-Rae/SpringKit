package __SPRINGKIT_PACKAGE_NAME__

import __SPRINGKIT_PACKAGE_NAME__.application.greeting.GreetingService
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
@ApplyExtension(SpringExtension::class)
class BatchApplicationTests(private val greetingService: GreetingService) :
    FunSpec({
        context("Batch 애플리케이션의 Spring context") {
            test("애플리케이션을 시작하면, GreetingService를 제공한다") {
                greetingService.greeting().message shouldBe "Hello, Springkit!"
            }
        }
    })
