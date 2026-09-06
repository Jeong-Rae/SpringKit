package __SPRINGKIT_PACKAGE_NAME__.application.greeting

import __SPRINGKIT_PACKAGE_NAME__.application.greeting.port.GreetingPort
import __SPRINGKIT_PACKAGE_NAME__.domain.greeting.Greeting

class GreetingService(
    private val greetingPort: GreetingPort,
) {
    fun greeting(): Greeting = greetingPort.load()
}
