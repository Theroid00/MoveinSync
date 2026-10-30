package com.moveinsync.rostersync.booking.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Interactive API docs at /swagger-ui.html (spec at /v3/api-docs), with JWT bearer authorization. */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    static final String BEARER = "bearer";

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("booking-service").version("1.0")
                        .description("Trips, bookings and derived seats, plus the idempotent consumer of roster change events. Use a token issued by roster-service (POST http://localhost:8080/api/v1/auth/token), then Authorize."))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
