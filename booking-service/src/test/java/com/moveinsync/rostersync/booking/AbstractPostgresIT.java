package com.moveinsync.rostersync.booking;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * One postgres:18 container per JVM, initialised with the same roles/databases script as compose.
 * The application connects as the <b>app role</b> (subject to RLS) and Flyway as the owner role.
 * Never use {@code @ServiceConnection} here: it would connect as the superuser and bypass RLS.
 */
public abstract class AbstractPostgresIT {

    protected static final String DB = "booking";
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withCopyFileToContainer(MountableFile.forHostPath(initScript()),
                    "/docker-entrypoint-initdb.d/01-roles-and-dbs.sql")
            .withCommand("postgres", "-c", "max_connections=400");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> jdbcUrl("socketTimeout=10&connectTimeout=2"));
        registry.add("spring.datasource.username", () -> DB + "_app");
        registry.add("spring.datasource.password", () -> DB + "_app_pw");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "2");
        registry.add("spring.flyway.user", () -> DB + "_owner");
        registry.add("spring.flyway.password", () -> DB + "_owner_pw");
        registry.add("app.bulk-datasource.url", () -> jdbcUrl("socketTimeout=330&connectTimeout=2"));
    }

    protected static String jdbcUrl(String params) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + DB + "?" + params;
    }

    /** Superuser connection for test fixtures and cross-tenant assertions only (bypasses RLS). */
    protected static JdbcClient superuserJdbc() {
        return JdbcClient.create(new DriverManagerDataSource(jdbcUrl(""), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static Path initScript() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("docker/postgres/init/01-roles-and-dbs.sql");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("docker/postgres/init/01-roles-and-dbs.sql not found above the working directory");
    }
}
