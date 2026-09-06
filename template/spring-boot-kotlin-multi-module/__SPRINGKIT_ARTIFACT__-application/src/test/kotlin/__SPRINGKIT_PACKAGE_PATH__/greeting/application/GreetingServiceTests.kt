package __SPRINGKIT_PACKAGE_NAME__.greeting.application

import __SPRINGKIT_PACKAGE_NAME__.greeting.domain.Greeting
import kotlin.test.Test
import kotlin.test.assertEquals

class GreetingServiceTests {

    @Test
    fun `포트에서 인사말을 가져온다`() {
        val service = GreetingService { Greeting("Hello, Springkit!") }

        assertEquals("Hello, Springkit!", service.greeting().message)
    }
}
