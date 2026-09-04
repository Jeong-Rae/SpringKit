package me.jeongrae.springkit

import kotlin.test.Test
import kotlin.test.assertEquals

class SpringKitTest {
    @Test
    fun `exposes the application name`() {
        assertEquals("SpringKit", APPLICATION_NAME)
    }
}
