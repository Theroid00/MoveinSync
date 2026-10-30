package com.moveinsync.rostersync.roster;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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

    protected static final String DB = "roster";
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withCopyFileToContainer(MountableFile.forHostPath(initScript()),
                    "/docker-entrypoint-initdb.d/01-roles-and-dbs.sql");

    /** Per-JVM scratch directory for the generated JWT key and reconciliation uploads. */
    protected static final Path TEMP_DIR;

    static {
        POSTGRES.start();
        try {
            TEMP_DIR = Files.createTempDirectory("roster-it-");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> jdbcUrl("socketTimeout=10&connectTimeout=2"));
        registry.add("spring.datasource.username", () -> DB + "_app");
        registry.add("spring.datasource.password", () -> DB + "_app_pw");
        registry.add("spring.flyway.user", () -> DB + "_owner");
        registry.add("spring.flyway.password", () -> DB + "_owner_pw");
        registry.add("app.bulk-datasource.url", () -> jdbcUrl("socketTimeout=330&connectTimeout=2"));
        // Tests drive the relay explicitly (OutboxRelay.runOnce) instead of the scheduler.
        registry.add("app.relay.enabled", () -> "false");
        registry.add("app.jwt.key-path", () -> TEMP_DIR.resolve("jwt/key.pem").toString());
        registry.add("app.recon.dir", () -> TEMP_DIR.resolve("recon").toString());
        // Recovery is invoked explicitly by the test that covers it (contexts are cached and shared).
        registry.add("app.recon.recover-on-startup", () -> "false");
    }

    protected static String jdbcUrl(String params) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + DB + "?" + params;
    }

    /** Superuser connection for test fixtures and cross-tenant assertions only (bypasses RLS). */
    protected static JdbcClient superuserJdbc() {
        return JdbcClient.create(new DriverManagerDataSource(jdbcUrl(""), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    /**
     * A fresh tenant with source {@code hr-main}, default brake config and offices BLR-A, BLR-B, GGN-A.
     * Random ids keep test classes that share the container independent.
     */
    protected static String newTenant() {
        String tenant = "t-" + UUID.randomUUID().toString().substring(0, 12);
        JdbcClient su = superuserJdbc();
        su.sql("INSERT INTO tenant_source (tenant_id, source_id, lock_key) VALUES (?, 'hr-main', ?)")
                .params(tenant, ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)).update();
        su.sql("INSERT INTO tenant_config (tenant_id) VALUES (?)").param(tenant).update();
        for (String office : new String[] {"BLR-A", "BLR-B", "GGN-A"}) {
            su.sql("INSERT INTO office (tenant_id, office_code) VALUES (?, ?)").params(tenant, office).update();
        }
        return tenant;
    }

    /** Row count of a tenant table, read as superuser (RLS bypassed for assertions only). */
    protected static long count(String table, String tenant) {
        return superuserJdbc().sql("SELECT count(*) FROM " + table + " WHERE tenant_id = ?").param(tenant)
                .query(Long.class).single();
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
