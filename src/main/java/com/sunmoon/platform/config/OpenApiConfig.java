package com.sunmoon.platform.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI sunMoonOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("sun-moon-java-platform API")
                        .description("DDD-based order service — REST surface of the sun-moon-java-platform scaffold.")
                        .version("0.1.0"))
                // Relative URL, not the request-derived "Generated server url"
                // (which would otherwise show the host's public IP in the
                // Swagger UI "Servers" dropdown). Browsers/Swagger UI resolve
                // it against the page's own origin, so "Try it out" still
                // works correctly.
                .servers(List.of(new Server().url("/sun-moon-java-platform")));
    }
}
