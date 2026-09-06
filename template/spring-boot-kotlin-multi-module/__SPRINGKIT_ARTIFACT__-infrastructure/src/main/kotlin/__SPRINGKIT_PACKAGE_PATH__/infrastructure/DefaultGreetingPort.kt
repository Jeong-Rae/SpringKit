package __SPRINGKIT_PACKAGE_NAME__.infrastructure

import __SPRINGKIT_PACKAGE_NAME__.application.GreetingPort
import __SPRINGKIT_PACKAGE_NAME__.domain.Greeting

class DefaultGreetingPort : GreetingPort {
    override fun load(): Greeting = Greeting("Hello, Springkit!")
}
