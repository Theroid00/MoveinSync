package com.moveinsync.rostersync.booking.api;

import java.net.URI;
import java.util.List;
import java.util.Map;

import com.moveinsync.rostersync.contract.ErrorCode;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** RFC 9457 body: fixed title per code, errorCode, traceId, optional field errors. */
public final class Problems {

    private Problems() {
    }

    public static ProblemDetail of(ErrorCode code, List<Map<String, String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatusCode.valueOf(code.httpStatus()));
        problem.setType(URI.create("urn:roster-sync:error:" + code.name().toLowerCase().replace('_', '-')));
        problem.setTitle(code.title());
        problem.setProperty("errorCode", code.name());
        problem.setProperty("traceId", MDC.get("traceId"));
        if (errors != null && !errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return problem;
    }
}
