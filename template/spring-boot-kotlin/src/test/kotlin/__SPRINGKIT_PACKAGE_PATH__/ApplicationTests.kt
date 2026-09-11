package __SPRINGKIT_PACKAGE_NAME__

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.string.shouldNotBeBlank
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

@SpringBootTest
@ApplyExtension(SpringExtension::class)
class ApplicationTests(private val applicationContext: ApplicationContext) :
    FunSpec({
        context("애플리케이션의 Spring context") {
            test("애플리케이션을 시작하면, context를 구성한다") {
                applicationContext.id.shouldNotBeBlank()
            }
        }
    })
