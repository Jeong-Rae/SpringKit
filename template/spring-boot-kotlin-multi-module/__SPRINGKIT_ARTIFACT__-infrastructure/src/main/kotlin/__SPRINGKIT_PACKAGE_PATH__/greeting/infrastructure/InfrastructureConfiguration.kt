package __SPRINGKIT_PACKAGE_NAME__.greeting.infrastructure

import __SPRINGKIT_PACKAGE_NAME__.greeting.application.GreetingPort
import __SPRINGKIT_PACKAGE_NAME__.greeting.application.GreetingService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class InfrastructureConfiguration {

    @Bean
    fun greetingPort(): GreetingPort = DefaultGreetingPort()

    @Bean
    fun greetingService(greetingPort: GreetingPort): GreetingService = GreetingService(greetingPort)
}
