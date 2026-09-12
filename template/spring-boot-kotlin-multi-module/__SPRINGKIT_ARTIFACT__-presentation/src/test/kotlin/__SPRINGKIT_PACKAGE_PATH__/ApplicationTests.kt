package __SPRINGKIT_PACKAGE_NAME__

import __SPRINGKIT_PACKAGE_NAME__.application.greeting.GreetingService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class ApplicationTests {
  @Autowired private lateinit var greetingService: GreetingService

  @Test
  fun `애플리케이션을 시작하면 GreetingService를 제공합니다`() {
    assertEquals("Hello, Springkit!", greetingService.greeting().message)
  }
}
