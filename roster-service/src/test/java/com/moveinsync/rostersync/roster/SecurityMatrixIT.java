package com.moveinsync.rostersync.roster;

import static com.moveinsync.rostersync.roster.Events.upsert;
import static com.moveinsync.rostersync.roster.RosterApi.Role.ADMIN;
import static com.moveinsync.rostersync.roster.RosterApi.Role.EMPLOYEE;
import static com.moveinsync.rostersync.roster.RosterApi.Role.HR_INTEGRATION;
import static com.moveinsync.rostersync.roster.RosterApi.Role.OPERATOR;
import static com.moveinsync.rostersync.roster.RosterApi.Role.SERVICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.moveinsync.rostersync.roster.RosterApi.Response;
import com.moveinsync.rostersync.roster.RosterApi.Role;
import com.moveinsync.rostersync.roster.security.TokenService;
import com.moveinsync.rostersync.roster.security.TokenService.TokenSpec;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Every cell of the spec §12.3 authorization matrix for roster-service endpoints, plus tenant
 * isolation (another tenant's resource is 404) and token validation (401s).
 */
class SecurityMatrixIT extends AbstractApiIT {

    enum Expect { ALLOW, DENY }

    record Endpoint(String name, Supplier<AbstractMockHttpServletRequestBuilder<?>> request) {
        @Override
        public String toString() {
            return name;
        }
    }

    static final String EVENT = "{\"eventId\":\"m-1\",\"sourceSeq\":9,\"eventType\":\"UPSERT\",\"employeeId\":\"EMP-009\","
            + "\"effectiveAt\":\"2026-10-30T08:00:00Z\",\"hr\":{\"name\":\"N\",\"email\":\"n@example.test\","
            + "\"phone\":\"+919000000009\",\"managerId\":null,\"officeCode\":\"BLR-A\",\"shiftCode\":\"NIGHT\"}}";

    static Endpoint ep(String name, Supplier<AbstractMockHttpServletRequestBuilder<?>> request) {
        return new Endpoint(name, request);
    }

    /** Rows of §12.3 (roster endpoints, incl. reconciliation). Order of expectations: EMPLOYEE, OPERATOR, ADMIN, HR_INTEGRATION, SERVICE. */
    static final Map<Endpoint, List<Expect>> MATRIX = new java.util.LinkedHashMap<>();

    static {
        Expect A = Expect.ALLOW;
        Expect D = Expect.DENY;
        MATRIX.put(ep("POST /hr/events", () -> post("/api/v1/hr/events").contentType(MediaType.APPLICATION_JSON).content(EVENT)),
                List.of(D, D, D, A, D));
        MATRIX.put(ep("POST /hr/events/batch", () -> post("/api/v1/hr/events/batch").contentType("application/x-ndjson").content(EVENT + "\n")),
                List.of(D, D, D, A, D));
        MATRIX.put(ep("GET /employees/{self}", () -> get("/api/v1/employees/EMP-001")), List.of(A, A, A, D, D));
        MATRIX.put(ep("GET /employees/{other}", () -> get("/api/v1/employees/EMP-002")), List.of(D, A, A, D, D));
        MATRIX.put(ep("GET /employees/{id}/history", () -> get("/api/v1/employees/EMP-001/history")), List.of(D, D, A, D, D));
        MATRIX.put(ep("PATCH /employees/{id}/transport", () -> patch("/api/v1/employees/EMP-001/transport")
                .header(HttpHeaders.IF_MATCH, "1").contentType(MediaType.APPLICATION_JSON).content("{\"pickupPoint\":\"Gate 1\"}")),
                List.of(D, D, A, D, D));
        MATRIX.put(ep("DELETE /employees/{id}", () -> delete("/api/v1/employees/EMP-001")), List.of(D, D, A, D, D));
        MATRIX.put(ep("GET /outbox/status", () -> get("/api/v1/outbox/status")), List.of(D, A, D, D, D));
        MATRIX.put(ep("POST /outbox/{id}/replay", () -> post("/api/v1/outbox/999999999/replay")), List.of(D, A, D, D, D));
        String run = "/api/v1/reconciliations/" + UUID.randomUUID();
        MATRIX.put(ep("POST /reconciliations", () -> multipart("/api/v1/reconciliations")
                .file(new MockMultipartFile("file", "s.csv", "text/csv", Snapshot.csv(Snapshot.rows(1, 2)).getBytes()))
                .file(new MockMultipartFile("manifest", "", "application/json",
                        "{\"sourceId\":\"hr-main\",\"cutoffSeq\":10,\"exportedAt\":\"2026-10-30T02:00:00Z\"}".getBytes()))),
                List.of(D, A, D, A, D));
        MATRIX.put(ep("GET /reconciliations/{id}", () -> get(run)), List.of(D, A, A, D, D));
        MATRIX.put(ep("GET /reconciliations/{id}/errors", () -> get(run + "/errors")), List.of(D, A, A, D, D));
        MATRIX.put(ep("GET /reconciliations/pending-approval", () -> get("/api/v1/reconciliations/pending-approval")),
                List.of(D, A, A, D, D));
        MATRIX.put(ep("POST /reconciliations/{id}/approve", () -> post(run + "/approve").contentType(MediaType.APPLICATION_JSON)
                .content("{\"assessmentHash\":\"00\",\"reason\":\"ok\"}")), List.of(D, D, A, D, D));
        MATRIX.put(ep("POST /reconciliations/{id}/reject", () -> post(run + "/reject")), List.of(D, A, A, D, D));
        MATRIX.put(ep("POST /reconciliations/{id}/retry", () -> post(run + "/retry")), List.of(D, A, D, D, D));
    }

    static final Role[] COLUMNS = {EMPLOYEE, OPERATOR, ADMIN, HR_INTEGRATION, SERVICE};

    static Stream<Arguments> cells() {
        List<Arguments> cells = new ArrayList<>();
        MATRIX.forEach((endpoint, row) -> {
            for (int i = 0; i < COLUMNS.length; i++) {
                cells.add(Arguments.of(endpoint, COLUMNS[i], row.get(i)));
            }
        });
        return cells.stream();
    }

    @BeforeEach
    void twoEmployees() {
        api.postEvent(tenant, upsert("s-1", 1, "EMP-001"));
        api.postEvent(tenant, upsert("s-2", 2, "EMP-002"));
    }

    @ParameterizedTest(name = "{0} as {1} → {2}")
    @MethodSource("cells")
    void matrixCell(Endpoint endpoint, Role role, Expect expect) {
        Response r = api.perform(endpoint.request().get(), tenant, role, RosterApi.SELF);
        if (expect == Expect.DENY) {
            assertThat(r.status()).as("%s as %s", endpoint, role).isEqualTo(403);
            assertThat(r.text("errorCode")).isEqualTo("FORBIDDEN");
            assertThat(r.text("traceId")).isNotBlank();
        } else {
            assertThat(r.status()).as("%s as %s (%s)", endpoint, role, r.raw()).isNotIn(401, 403);
        }
    }

    @Test
    void crossTenantReadIs404() {
        String other = newTenant();
        assertThat(api.perform(get("/api/v1/employees/EMP-001"), other, ADMIN).status()).isEqualTo(404);
        assertThat(api.perform(get("/api/v1/employees/EMP-001"), other, EMPLOYEE, "EMP-001").status()).isEqualTo(404);
        assertThat(api.perform(get("/api/v1/employees/EMP-001/history"), other, ADMIN).status()).isEqualTo(404);
        assertThat(api.perform(patch("/api/v1/employees/EMP-001/transport").header(HttpHeaders.IF_MATCH, "1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"pickupPoint\":\"x\"}"), other, ADMIN).status()).isEqualTo(404);
        assertThat(api.perform(delete("/api/v1/employees/EMP-001"), other, ADMIN).status()).isEqualTo(404);
        assertThat(api.getEmployee(tenant, "EMP-001").body().get("active").asBoolean()).isTrue();
    }

    @Test
    void noTokenIs401() {
        for (MockHttpServletRequestBuilder request : List.of(get("/api/v1/employees/EMP-001"),
                post("/api/v1/hr/events").contentType(MediaType.APPLICATION_JSON).content(EVENT), get("/api/v1/outbox/status"))) {
            Response r = api.send(request);
            assertThat(r.status()).isEqualTo(401);
            assertThat(r.text("errorCode")).isEqualTo("UNAUTHENTICATED");
            assertThat(r.text("traceId")).isNotBlank();
            assertThat(r.header(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        }
    }

    @Test
    void expiredTokenIs401() {
        Instant past = Instant.now().minusSeconds(3600);
        String expired = tokens.issue(new TokenSpec("admin@" + tenant, tenant, List.of("ADMIN"), null,
                List.of(TokenService.AUD_ROSTER), past, past.plusSeconds(900)));
        Response r = api.send(get("/api/v1/employees/EMP-001").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired));
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.text("errorCode")).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void wrongAudienceIs401() {
        Instant now = Instant.now();
        String bookingOnly = tokens.issue(new TokenSpec("admin@" + tenant, tenant, List.of("ADMIN"), null,
                List.of(TokenService.AUD_BOOKING), now, now.plusSeconds(900)));
        assertThat(api.send(get("/api/v1/employees/EMP-001").header(HttpHeaders.AUTHORIZATION, "Bearer " + bookingOnly))
                .status()).isEqualTo(401);
        // The relay's real service token is booking-only too: it cannot call roster.
        assertThat(api.send(get("/api/v1/outbox/status").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.serviceToken()))
                .status()).isEqualTo(401);
    }

    @Test
    void tokenSignedByAnotherKeyIs401() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAKey foreign = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID("foreign").build();
        Instant now = Instant.now();
        String forged = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(foreign))).encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId("foreign").build(),
                JwtClaimsSet.builder().issuer(TokenService.ISSUER).subject("x").audience(List.of(TokenService.AUD_ROSTER))
                        .issuedAt(now).expiresAt(now.plusSeconds(900)).claim("tenant", tenant).claim("roles", List.of("ADMIN"))
                        .build())).getTokenValue();
        assertThat(api.send(get("/api/v1/employees/EMP-001").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .status()).isEqualTo(401);
    }

    @Test
    void devIssuerAndJwksArePublic() {
        Response token = api.send(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin@acme\",\"password\":\"demo123\"}"));
        assertThat(token.status()).isEqualTo(200);
        assertThat(token.body().get("expiresIn").asLong()).isEqualTo(900);
        Response me = api.send(get("/api/v1/outbox/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.text("accessToken")));
        assertThat(me.status()).isEqualTo(403);                       // a valid ADMIN token; outbox is OPERATOR-only

        Response wrong = api.send(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin@acme\",\"password\":\"nope\"}"));
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.raw()).doesNotContain("nope");

        Response jwks = api.send(get("/.well-known/jwks.json"));
        assertThat(jwks.status()).isEqualTo(200);
        assertThat(jwks.body().get("keys").get(0).get("kty").asString()).isEqualTo("RSA");
        assertThat(jwks.body().get("keys").get(0).has("d")).isFalse();  // no private part
    }
}
