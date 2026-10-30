package com.moveinsync.rostersync.roster.api;

import java.util.List;

import com.moveinsync.rostersync.contract.ErrorCode;
import com.moveinsync.rostersync.roster.core.FieldError;
import com.moveinsync.rostersync.roster.service.DomainException;
import com.moveinsync.rostersync.roster.service.ReconciliationInProgressException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Maps every failure to a ProblemDetail with errorCode + traceId (spec §13.1). Titles are fixed per
 * code; rejected values, SQL and stack traces never reach the response.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    static final String RETRY_AFTER_SECONDS = "5";

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> domain(DomainException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.code().httpStatus());
        if (e instanceof ReconciliationInProgressException) {
            response.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return response.body(Problems.of(e.code(), e.errors()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException e) {
        return respond(jsonProblem(e.getCause()));
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> badParameter(Exception e) {
        String field = e instanceof MissingRequestHeaderException m ? m.getHeaderName()
                : ((MethodArgumentTypeMismatchException) e).getName();
        return respond(Problems.of(ErrorCode.VALIDATION_FAILED, List.of(new FieldError(field, "INVALID_PARAMETER"))));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> tooLarge(MaxUploadSizeExceededException e) {
        return respond(Problems.of(ErrorCode.PAYLOAD_TOO_LARGE));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        if (e instanceof ErrorResponse framework) {
            return framework(framework.getStatusCode().value());
        }
        if (DbFailures.isUnavailable(e)) {
            log.warn("event=db_unavailable exception={}", e.getClass().getSimpleName());
            return ResponseEntity.status(ErrorCode.DB_UNAVAILABLE.httpStatus())
                    .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                    .body(Problems.of(ErrorCode.DB_UNAVAILABLE));
        }
        log.error("event=unhandled_error exception={}", e.getClass().getName(), e);
        return respond(Problems.of(ErrorCode.INTERNAL_ERROR));
    }

    /** Spring MVC's own errors (404 no handler, 405, 415, ...): keep the status, use our body. */
    private static ResponseEntity<ProblemDetail> framework(int status) {
        ErrorCode code = switch (status) {
            case 404 -> ErrorCode.NOT_FOUND;
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
            case 400 -> ErrorCode.MALFORMED_JSON;
            default -> status >= 500 ? ErrorCode.INTERNAL_ERROR : ErrorCode.VALIDATION_FAILED;
        };
        ProblemDetail problem = Problems.of(code);
        problem.setStatus(status);
        return ResponseEntity.status(status).body(problem);
    }

    /** Classifies a JSON binding failure: unknown property, wrong shape/type, or not JSON at all. */
    public static ProblemDetail jsonProblem(Throwable cause) {
        if (cause instanceof UnrecognizedPropertyException u) {
            return Problems.of(ErrorCode.UNKNOWN_FIELD, List.of(new FieldError(path(u), "UNKNOWN_FIELD")));
        }
        if (cause instanceof DatabindException d && !d.getPath().isEmpty()) {
            return Problems.of(ErrorCode.MALFORMED_JSON, List.of(new FieldError(path(d), "MALFORMED_JSON")));
        }
        return Problems.of(ErrorCode.MALFORMED_JSON);
    }

    /** Dotted property path such as {@code hr.pickupPoint}; array positions as {@code [i]}. Names only. */
    public static String path(JacksonException e) {
        StringBuilder sb = new StringBuilder();
        for (JacksonException.Reference ref : e.getPath()) {
            if (ref.getPropertyName() != null) {
                sb.append(sb.isEmpty() ? "" : ".").append(ref.getPropertyName());
            } else if (ref.getIndex() >= 0) {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.toString();
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
