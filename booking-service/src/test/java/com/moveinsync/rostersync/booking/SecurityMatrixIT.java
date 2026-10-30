package com.moveinsync.rostersync.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import com.moveinsync.rostersync.booking.BookingApi.Response;
import com.moveinsync.rostersync.contract.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The booking rows of the spec §12.3 matrix, tenant isolation and token validation. */
class SecurityMatrixIT extends AbstractBookingApiIT {

    enum Expect { ALLOW, DENY }

    static final String[] COLUMNS = {"EMPLOYEE", "OPERATOR", "ADMIN", "HR_INTEGRATION", "SERVICE"};

    /** Built per test from the fixture trip; order of expectations as COLUMNS. */
    record Row(String name, Function<SecurityMatrixIT, MockHttpServletRequestBuilder> request, List<Expect> expect) {
        @Override
        public String toString() {
            return name;
        }
    }

    static final Expect A = Expect.ALLOW;
    static final Expect D = Expect.DENY;

    static final List<Row> ROWS = List.of(
            new Row("POST /trips", t -> post("/api/v1/trips").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"capacity\":4,\"startsAt\":\"2030-01-01T08:00:00Z\"}"), List.of(D, D, A, D, D)),
            new Row("POST /bookings (self)", t -> post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"employeeId\":\"EMP-001\",\"tripId\":\"" + t.tripId + "\"}"), List.of(A, D, A, D, D)),
            new Row("POST /bookings (other)", t -> post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"employeeId\":\"EMP-002\",\"tripId\":\"" + t.tripId + "\"}"), List.of(D, D, A, D, D)),
            new Row("GET /trips/{id}/seats", t -> get("/api/v1/trips/" + t.tripId + "/seats"), List.of(A, A, A, D, D)),
            new Row("POST /internal/events", t -> post("/internal/events").contentType(MediaType.APPLICATION_JSON)
                    .content(t.json.writeValueAsString(BookingApi.event(t.tenant, "EMP-003", EventType.EmployeeCreated, 1, true,
                            Instant.now()))), List.of(D, D, D, D, A)));

    static Stream<Arguments> cells() {
        List<Arguments> cells = new ArrayList<>();
        for (Row row : ROWS) {
            for (int i = 0; i < COLUMNS.length; i++) {
                cells.add(Arguments.of(row, COLUMNS[i], row.expect().get(i)));
            }
        }
        return cells.stream();
    }

    UUID tripId;

    @BeforeEach
    void fixtures() {
        activate("EMP-001");
        activate("EMP-002");
        tripId = futureTrip(4, 2);
    }

    @ParameterizedTest(name = "{0} as {1} → {2}")
    @MethodSource("cells")
    void matrixCell(Row row, String role, Expect expect) {
        Response r = api.perform(row.request().apply(this), tenant, role, "EMP-001");
        if (expect == Expect.DENY) {
            assertThat(r.status()).as("%s as %s", row, role).isEqualTo(403);
            assertThat(r.text("errorCode")).isEqualTo("FORBIDDEN");
        } else {
            assertThat(r.status()).as("%s as %s (%s)", row, role, r.raw()).isNotIn(401, 403);
        }
    }

    @Test
    void crossTenantReadIs404() {
        String bookingId = api.bookAsAdmin(tenant, "EMP-001", tripId).text("bookingId");
        String other = "t-" + UUID.randomUUID().toString().substring(0, 12);
        assertThat(api.perform(get("/api/v1/bookings/" + bookingId), other, "ADMIN", null).status()).isEqualTo(404);
        assertThat(api.perform(get("/api/v1/bookings/" + bookingId), other, "EMPLOYEE", "EMP-001").status()).isEqualTo(404);
        assertThat(api.perform(get("/api/v1/trips/" + tripId + "/seats"), other, "ADMIN", null).status()).isEqualTo(404);
        // Same tenant, another employee: not theirs, also 404 (no existence leak).
        assertThat(api.perform(get("/api/v1/bookings/" + bookingId), tenant, "EMPLOYEE", "EMP-002").status()).isEqualTo(404);
    }

    @Test
    void noTokenIs401() {
        Response r = api.send(get("/api/v1/trips/" + tripId + "/seats"));
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.text("errorCode")).isEqualTo("UNAUTHENTICATED");
        assertThat(api.send(post("/internal/events").contentType(MediaType.APPLICATION_JSON).content("{}")).status())
                .isEqualTo(401);
    }

    @Test
    void expiredTokenIs401() {
        Instant past = Instant.now().minusSeconds(3600);
        String expired = TestTokens.mint(tenant, List.of("ADMIN"), null, List.of("booking"), past, past.plusSeconds(900));
        assertThat(api.send(get("/api/v1/trips/" + tripId + "/seats").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .status()).isEqualTo(401);
    }

    @Test
    void wrongAudienceIs401() {
        Instant now = Instant.now();
        String rosterOnly = TestTokens.mint(tenant, List.of("ADMIN"), null, List.of("roster-sync"), now, now.plusSeconds(900));
        assertThat(api.send(get("/api/v1/trips/" + tripId + "/seats").header(HttpHeaders.AUTHORIZATION, "Bearer " + rosterOnly))
                .status()).isEqualTo(401);
    }
}
