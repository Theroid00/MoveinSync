package com.moveinsync.rostersync.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.moveinsync.rostersync.booking.BookingApi.Response;
import org.junit.jupiter.api.Test;

/** Interactive docs are public, describe the real endpoints, and advertise JWT bearer authorization. */
class OpenApiIT extends AbstractBookingApiIT {

    @Test
    void apiDescriptionIsPublicAndComplete() {
        Response docs = api.send(get("/v3/api-docs"));
        assertThat(docs.status()).isEqualTo(200);
        assertThat(docs.body().get("paths").propertyNames()).contains("/api/v1/trips", "/api/v1/trips/{id}/seats",
                "/api/v1/bookings", "/api/v1/bookings/{id}", "/api/v1/employees/{id}/bookings", "/internal/events");
        assertThat(docs.body().at("/components/securitySchemes/bearer/scheme").asString()).isEqualTo("bearer");
    }

    @Test
    void swaggerUiLoadsWithoutAToken() {
        Response ui = api.send(get("/swagger-ui/index.html"));
        assertThat(ui.status()).isEqualTo(200);
        assertThat(ui.raw()).contains("swagger-ui");
    }
}
