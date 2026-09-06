package __SPRINGKIT_PACKAGE_NAME__.application

import __SPRINGKIT_PACKAGE_NAME__.domain.Greeting

class GreetingService(
    private val greetingPort: GreetingPort,
) {
    fun greeting(): Greeting = greetingPort.load()
}
