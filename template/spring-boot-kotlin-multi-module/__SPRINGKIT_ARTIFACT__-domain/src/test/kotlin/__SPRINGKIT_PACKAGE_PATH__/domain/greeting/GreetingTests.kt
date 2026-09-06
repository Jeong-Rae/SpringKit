package __SPRINGKIT_PACKAGE_NAME__.domain.greeting

import kotlin.test.Test
import kotlin.test.assertEquals

class GreetingTests {

    @Test
    fun `인사말을 보관한다`() {
        val greeting = Greeting("Hello, Springkit!")

        assertEquals("Hello, Springkit!", greeting.message)
    }
}
