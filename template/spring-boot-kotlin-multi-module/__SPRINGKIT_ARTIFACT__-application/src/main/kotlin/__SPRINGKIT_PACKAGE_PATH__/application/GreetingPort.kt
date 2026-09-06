package __SPRINGKIT_PACKAGE_NAME__.application

import __SPRINGKIT_PACKAGE_NAME__.domain.Greeting

fun interface GreetingPort {
    fun load(): Greeting
}
