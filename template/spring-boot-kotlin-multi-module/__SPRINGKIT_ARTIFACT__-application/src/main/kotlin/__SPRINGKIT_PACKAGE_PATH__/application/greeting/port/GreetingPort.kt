package __SPRINGKIT_PACKAGE_NAME__.application.greeting.port

import __SPRINGKIT_PACKAGE_NAME__.domain.greeting.Greeting

fun interface GreetingPort {
    fun load(): Greeting
}
