package __SPRINGKIT_PACKAGE_NAME__.greeting.application

import __SPRINGKIT_PACKAGE_NAME__.greeting.domain.Greeting

class GreetingService(
    private val greetingPort: GreetingPort,
) {
    fun greeting(): Greeting = greetingPort.load()
}
