package com.moveinsync.rostersync.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.moveinsync.rostersync.roster.api.DbFailures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The bulk pool (socketTimeout 330 s, statement_timeout 300 s) survives a statement that the primary
 * pool (socketTimeout 10 s, role statement_timeout 5 s) must cut off with DB_UNAVAILABLE (spec §14).
 */
@SpringBootTest
class BulkPoolIT extends AbstractPostgresIT {

    @Autowired
    JdbcClient primary;

    @Autowired
    @Qualifier("bulk")
    JdbcClient bulk;

    @Test
    void longApplyNotKilledBySocketTimeout() {
        assertThat(bulk.sql("SELECT pg_sleep(15)::text").query(String.class).single()).isEmpty();

        Throwable failure = catchThrowable(() -> primary.sql("SELECT pg_sleep(15)::text").query(String.class).single());
        assertThat(failure).isNotNull();
        assertThat(DbFailures.isUnavailable(failure)).as("classified as DB_UNAVAILABLE: %s", failure).isTrue();
    }
}
