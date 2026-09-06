package __SPRINGKIT_PACKAGE_NAME__.greeting.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultGreetingPortTests {

    @Test
    fun `기본 인사말을 제공한다`() {
        val greetingPort = DefaultGreetingPort()

        assertEquals("Hello, Springkit!", greetingPort.load().message)
    }
}
