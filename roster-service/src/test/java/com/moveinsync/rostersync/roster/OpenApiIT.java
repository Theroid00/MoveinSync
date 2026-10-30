package com.moveinsync.rostersync.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.moveinsync.rostersync.roster.RosterApi.Response;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Interactive docs are public, describe the real endpoints, and advertise JWT bearer authorization. */
class OpenApiIT extends AbstractApiIT {

    @Test
    void apiDescriptionIsPublicAndComplete() {
        Response docs = api.send(get("/v3/api-docs"));
        assertThat(docs.status()).isEqualTo(200);
        JsonNode paths = docs.body().get("paths");
        assertThat(paths.propertyNames()).contains("/api/v1/hr/events", "/api/v1/hr/events/batch",
                "/api/v1/employees/{id}", "/api/v1/employees/{id}/transport", "/api/v1/reconciliations",
                "/api/v1/reconciliations/{id}/approve", "/api/v1/outbox/status", "/api/v1/auth/token");
        assertThat(docs.body().at("/components/securitySchemes/bearer/scheme").asString()).isEqualTo("bearer");
        // Obtaining a token needs no token.
        assertThat(paths.at("/~1api~1v1~1auth~1token/post/security").isEmpty()).isTrue();
    }

    @Test
    void swaggerUiLoadsWithoutAToken() {
        Response ui = api.send(get("/swagger-ui/index.html"));
        assertThat(ui.status()).isEqualTo(200);
        assertThat(ui.raw()).contains("swagger-ui");
        assertThat(api.send(get("/swagger-ui.html")).status()).isIn(200, 302);
    }
}
