package __SPRINGKIT_PACKAGE_NAME__.greeting.infrastructure

import __SPRINGKIT_PACKAGE_NAME__.greeting.application.GreetingPort
import __SPRINGKIT_PACKAGE_NAME__.greeting.domain.Greeting

class DefaultGreetingPort : GreetingPort {
    override fun load(): Greeting = Greeting("Hello, Springkit!")
}
