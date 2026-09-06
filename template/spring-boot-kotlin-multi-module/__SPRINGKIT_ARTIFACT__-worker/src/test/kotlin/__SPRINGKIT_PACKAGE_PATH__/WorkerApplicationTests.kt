package __SPRINGKIT_PACKAGE_NAME__

import __SPRINGKIT_PACKAGE_NAME__.greeting.application.GreetingService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals

@SpringBootTest
class WorkerApplicationTests {

    @Autowired
    private lateinit var greetingService: GreetingService

    @Test
    fun contextLoads() {
        assertEquals("Hello, Springkit!", greetingService.greeting().message)
    }
}
