package com.moveinsync.rostersync.roster.api;

import java.net.SocketTimeoutException;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/** Classifies failures that mean "the database is unavailable right now" (→ 503 DB_UNAVAILABLE). */
public final class DbFailures {

    private DbFailures() {
    }

    public static boolean isUnavailable(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof CannotGetJdbcConnectionException
                    || c instanceof QueryTimeoutException
                    || c instanceof TransientDataAccessResourceException
                    || c instanceof DataAccessResourceFailureException
                    || c instanceof SocketTimeoutException) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }
}
