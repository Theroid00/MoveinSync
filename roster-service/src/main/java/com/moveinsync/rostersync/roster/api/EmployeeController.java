package com.moveinsync.rostersync.roster.api;

import java.util.List;

import com.moveinsync.rostersync.roster.core.FieldError;
import com.moveinsync.rostersync.roster.security.TenantContext;
import com.moveinsync.rostersync.roster.service.EmployeeService;
import com.moveinsync.rostersync.roster.service.EmployeeService.EmployeeHistory;
import com.moveinsync.rostersync.roster.service.ValidationFailedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {

    private final EmployeeService employees;

    public EmployeeController(EmployeeService employees) {
        this.employees = employees;
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmployeeView> get(@PathVariable String id) {
        EmployeeView view = EmployeeView.from(employees.get(TenantContext.current(), id));
        return ResponseEntity.ok().eTag(Long.toString(view.aggregateRevision())).body(view);
    }

    @GetMapping("/{id}/history")
    public EmployeeHistory history(@PathVariable String id) {
        return employees.history(TenantContext.current(), id);
    }

    public record TransportResponse(String employeeId, long aggregateRevision) {
    }

    @PatchMapping(path = "/{id}/transport", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TransportResponse> updateTransport(@PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody TransportPatchRequest body) {
        long revision = employees.updateTransport(TenantContext.current(), id, parseIfMatch(ifMatch),
                body.pickupPoint(), body.specialAssistance());
        return ResponseEntity.ok().eTag(Long.toString(revision)).body(new TransportResponse(id, revision));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable String id) {
        long revision = employees.deactivate(TenantContext.current(), id);
        return ResponseEntity.noContent().eTag(Long.toString(revision)).build();
    }

    /** Accepts {@code 5}, {@code "5"} or {@code W/"5"}. */
    static long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ValidationFailedException(List.of(new FieldError("If-Match", "IF_MATCH_REQUIRED")));
        }
        String v = ifMatch.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        v = v.replace("\"", "");
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new ValidationFailedException(List.of(new FieldError("If-Match", "IF_MATCH_INVALID")));
        }
    }
}
