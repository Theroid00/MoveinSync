package com.moveinsync.rostersync.booking;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moveinsync.rostersync.contract.EmployeeChangedEvent;
import com.moveinsync.rostersync.contract.EventType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Test client for booking-service; authentication details live here only. */
public final class BookingApi {

    public record Response(int status, String raw, JsonNode body) {
        public String text(String field) {
            JsonNode n = body == null ? null : body.get(field);
            return n == null || n.isNull() ? null : n.asString();
        }
    }

    private final MockMvc mvc;
    private final JsonMapper json;

    public BookingApi(MockMvc mvc, JsonMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    public static EmployeeChangedEvent event(String tenant, String employeeId, EventType type, long revision,
                                             boolean active, Instant effectiveAt) {
        return new EmployeeChangedEvent(UUID.randomUUID(), type, tenant, employeeId, revision, active, effectiveAt,
                List.of("active"));
    }

    public Response consume(EmployeeChangedEvent event) {
        return perform(post("/internal/events").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", event.eventId().toString())
                .content(json.writeValueAsString(event)), "*", "SERVICE", null);
    }

    public Response createTrip(String tenant, int capacity, Instant startsAt) {
        return perform(post("/api/v1/trips").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("capacity", capacity, "startsAt", startsAt.toString()))),
                tenant, "ADMIN", null);
    }

    public Response seats(String tenant, UUID tripId) {
        return perform(get("/api/v1/trips/{id}/seats", tripId), tenant, "ADMIN", null);
    }

    public Response book(String tenant, String role, String callerEmployeeId, String employeeId, UUID tripId) {
        return perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("employeeId", employeeId, "tripId", tripId.toString()))),
                tenant, role, callerEmployeeId);
    }

    public Response bookAsAdmin(String tenant, String employeeId, UUID tripId) {
        return book(tenant, "ADMIN", null, employeeId, tripId);
    }

    /** tenant == null sends no token at all. */
    public Response perform(MockHttpServletRequestBuilder request, String tenant, String role, String employeeId) {
        if (tenant != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forRole(tenant, role, employeeId));
        }
        return send(request);
    }

    public Response send(MockHttpServletRequestBuilder request) {
        try {
            MockHttpServletResponse r = mvc.perform(request).andReturn().getResponse();
            String raw = r.getContentAsString();
            String type = r.getContentType();
            return new Response(r.getStatus(), raw, raw.isBlank() || type == null || !type.contains("json") ? null : json.readTree(raw));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
