package com.moveinsync.rostersync.booking.api;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

import com.moveinsync.rostersync.booking.service.DomainException;
import com.moveinsync.rostersync.contract.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/** Same error contract as roster-service (spec §13.1): ProblemDetail + errorCode + traceId, no leaks. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> domain(DomainException e) {
        return ResponseEntity.status(e.code().httpStatus()).body(Problems.of(e.code(), e.errors()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> forbidden(AccessDeniedException e) {
        return respond(Problems.of(ErrorCode.FORBIDDEN, List.of()));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> unauthenticated(AuthenticationException e) {
        return respond(Problems.of(ErrorCode.UNAUTHENTICATED, List.of()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException e) {
        if (e.getCause() instanceof UnrecognizedPropertyException u) {
            return respond(Problems.of(ErrorCode.UNKNOWN_FIELD,
                    List.of(Map.of("field", u.getPropertyName(), "code", "UNKNOWN_FIELD"))));
        }
        return respond(Problems.of(ErrorCode.MALFORMED_JSON, List.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        if (e instanceof ErrorResponse framework) {
            int status = framework.getStatusCode().value();
            ErrorCode code = status == 404 ? ErrorCode.NOT_FOUND
                    : status >= 500 ? ErrorCode.INTERNAL_ERROR
                    : status == 400 ? ErrorCode.MALFORMED_JSON : ErrorCode.VALIDATION_FAILED;
            ProblemDetail problem = Problems.of(code, List.of());
            problem.setStatus(status);
            return ResponseEntity.status(status).body(problem);
        }
        if (isDbUnavailable(e)) {
            log.warn("event=db_unavailable exception={}", e.getClass().getSimpleName());
            return ResponseEntity.status(ErrorCode.DB_UNAVAILABLE.httpStatus()).header(HttpHeaders.RETRY_AFTER, "5")
                    .body(Problems.of(ErrorCode.DB_UNAVAILABLE, List.of()));
        }
        log.error("event=unhandled_error exception={}", e.getClass().getName(), e);
        return respond(Problems.of(ErrorCode.INTERNAL_ERROR, List.of()));
    }

    static boolean isDbUnavailable(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof DataAccessResourceFailureException || c instanceof QueryTimeoutException
                    || c instanceof TransientDataAccessResourceException || c instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
