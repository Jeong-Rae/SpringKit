package __SPRINGKIT_PACKAGE_NAME__.infrastructure.greeting

import __SPRINGKIT_PACKAGE_NAME__.application.greeting.GreetingService
import __SPRINGKIT_PACKAGE_NAME__.application.greeting.port.GreetingPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class InfrastructureConfiguration {

    @Bean
    fun greetingPort(): GreetingPort = DefaultGreetingPort()

    @Bean
    fun greetingService(greetingPort: GreetingPort): GreetingService = GreetingService(greetingPort)
}
