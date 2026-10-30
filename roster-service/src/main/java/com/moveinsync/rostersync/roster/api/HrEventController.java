package com.moveinsync.rostersync.roster.api;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.moveinsync.rostersync.contract.ErrorCode;
import com.moveinsync.rostersync.roster.core.HrEventRequest;
import com.moveinsync.rostersync.roster.security.TenantContext;
import com.moveinsync.rostersync.roster.service.BatchIngestService;
import com.moveinsync.rostersync.roster.service.BatchIngestService.BatchResult;
import com.moveinsync.rostersync.roster.service.HrEventService;
import com.moveinsync.rostersync.roster.service.IngestResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HR event ingestion: one event, or an NDJSON batch with a per-line report. */
@RestController
@RequestMapping("/api/v1/hr/events")
public class HrEventController {

    public static final String NDJSON = "application/x-ndjson";

    private final HrEventService events;
    private final BatchIngestService batch;

    public HrEventController(HrEventService events, BatchIngestService batch) {
        this.events = events;
        this.batch = batch;
    }

    /** APPLIED/TOMBSTONED/DUPLICATE/STALE → 200; CONFLICT → 409; REJECTED → 422 (ProblemDetail + result fields). */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> ingest(@RequestBody HrEventRequest request) {
        IngestResult r = events.ingest(TenantContext.current(), request);
        return switch (r.outcome()) {
            case CONFLICT -> problem(ErrorCode.valueOf(r.errors().getFirst().code()), r);
            case REJECTED -> problem(ErrorCode.VALIDATION_FAILED, r);
            default -> ResponseEntity.ok(IngestResponse.from(r));
        };
    }

    @PostMapping(path = "/batch", consumes = NDJSON)
    public BatchResult ingestBatch(HttpServletRequest request) throws IOException {
        return batch.ingest(TenantContext.current(), new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8));
    }

    private static ResponseEntity<ProblemDetail> problem(ErrorCode code, IngestResult r) {
        ProblemDetail problem = Problems.of(code, r.errors());
        problem.setProperty("eventId", r.eventId());
        problem.setProperty("employeeId", r.employeeId());
        problem.setProperty("outcome", r.outcome());
        if (r.rejectionId() != null) {
            problem.setProperty("rejectionId", r.rejectionId());
        }
        return ResponseEntity.status(code.httpStatus()).body(problem);
    }
}
