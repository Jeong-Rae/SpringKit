package com.gihub.jeongrae.springkit.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

@Configuration
public class SwaggerConfig {

    @Value("${app.version:v0.0.1}")
    private String APP_VERSION;

    private static final String TITLE = "Project Title";
    private static final String DESCRIPTION = "Project Description";

    @Bean
    public OpenAPI openAPI() {
        Info info = new Info()
                .title(TITLE)
                .description(DESCRIPTION)
                .version(APP_VERSION);

        SecurityScheme securityScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .name("Authorization");

        SecurityRequirement securityRequirement = new SecurityRequirement()
                .addList("Authorization");

        return new OpenAPI()
                .info(info)
                .components(new Components().addSecuritySchemes("Authorization", securityScheme))
                .security(Collections.singletonList(securityRequirement));
    }
}
