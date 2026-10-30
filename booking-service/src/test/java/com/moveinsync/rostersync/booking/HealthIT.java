package com.moveinsync.rostersync.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** P0 smoke: the app boots against postgres:18, reports UP, and runs as the app role. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthIT extends AbstractPostgresIT {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    @Test
    void healthEndpointIsUp() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"UP\"");
    }

    @Test
    void runtimeConnectionUsesAppRoleNotSuperuser() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("booking_app");
        assertThat(jdbc.sql("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user")
                .query(Boolean.class).single()).isFalse();
    }
}
