package com.moveinsync.rostersync.roster.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moveinsync.rostersync.roster.core.FieldError;
import com.moveinsync.rostersync.roster.core.HrEventRequest;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

/**
 * NDJSON batch (spec §8): at most 1,000 lines, each line through the single-event path in its own
 * transaction, so one bad record never fails the batch. Returns a per-line report.
 */
@Service
public class BatchIngestService {

    public static final int MAX_LINES = 1000;

    private final HrEventService events;
    private final JsonMapper json;

    public BatchIngestService(HrEventService events, JsonMapper json) {
        this.events = events;
        this.json = json;
    }

    public record LineResult(int line, String eventId, String employeeId, IngestOutcome outcome, List<FieldError> errors) {
    }

    public record BatchResult(List<LineResult> results, Map<IngestOutcome, Integer> summary) {
    }

    public BatchResult ingest(String tenantId, Reader body) throws IOException {
        List<String> lines = readBounded(body);
        List<LineResult> results = new ArrayList<>();
        EnumMap<IngestOutcome, Integer> counts = new EnumMap<>(IngestOutcome.class);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            IngestResult r = ingestLine(tenantId, line);
            results.add(new LineResult(i + 1, r.eventId(), r.employeeId(), r.outcome(), r.errors()));
            counts.merge(r.outcome(), 1, Integer::sum);
        }
        Map<IngestOutcome, Integer> summary = new LinkedHashMap<>();
        for (IngestOutcome outcome : IngestOutcome.values()) {
            summary.put(outcome, counts.getOrDefault(outcome, 0));
        }
        return new BatchResult(results, summary);
    }

    private IngestResult ingestLine(String tenantId, String line) {
        HrEventRequest request;
        try {
            request = json.readValue(line, HrEventRequest.class);
        } catch (UnrecognizedPropertyException e) {
            return events.rejectUnparseable(tenantId, new FieldError(e.getPropertyName(), "UNKNOWN_FIELD"));
        } catch (JacksonException e) {
            return events.rejectUnparseable(tenantId, new FieldError(null, "MALFORMED_JSON"));
        }
        if (request == null) {
            return events.rejectUnparseable(tenantId, new FieldError(null, "MALFORMED_JSON"));
        }
        return events.ingest(tenantId, request);
    }

    private static List<String> readBounded(Reader body) throws IOException {
        List<String> lines = new ArrayList<>();
        int nonBlank = 0;
        try (BufferedReader reader = new BufferedReader(body)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank() && ++nonBlank > MAX_LINES) {
                    throw new PayloadTooLargeException();
                }
                lines.add(line);
            }
        }
        return lines;
    }
}
