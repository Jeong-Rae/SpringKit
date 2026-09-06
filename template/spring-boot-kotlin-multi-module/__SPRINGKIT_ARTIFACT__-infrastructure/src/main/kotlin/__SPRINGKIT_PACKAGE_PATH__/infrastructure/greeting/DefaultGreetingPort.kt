package __SPRINGKIT_PACKAGE_NAME__.infrastructure.greeting

import __SPRINGKIT_PACKAGE_NAME__.application.greeting.port.GreetingPort
import __SPRINGKIT_PACKAGE_NAME__.domain.greeting.Greeting

class DefaultGreetingPort : GreetingPort {
    override fun load(): Greeting = Greeting("Hello, Springkit!")
}
