package __SPRINGKIT_PACKAGE_NAME__.greeting.application

import __SPRINGKIT_PACKAGE_NAME__.greeting.domain.Greeting

fun interface GreetingPort {
    fun load(): Greeting
}
