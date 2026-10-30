package com.moveinsync.rostersync.roster.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;

class DbFailuresTest {

    @Test
    void failedRollbackPreservesDatabaseUnavailableResponse() {
        TransactionSystemException rollback = new TransactionSystemException("rollback failed",
                new SQLException("Connection is closed"));
        rollback.initApplicationException(new DataAccessResourceFailureException("database timed out"));

        var response = new GlobalExceptionHandler().unexpected(rollback);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
        assertThat(response.getBody().getProperties()).containsEntry("errorCode", "DB_UNAVAILABLE");
        assertThat(DbFailures.isUnavailable(new TransactionSystemException("other failure"))).isFalse();
    }
}
