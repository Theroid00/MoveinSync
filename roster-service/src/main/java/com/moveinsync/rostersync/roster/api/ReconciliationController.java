package com.moveinsync.rostersync.roster.api;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import com.moveinsync.rostersync.roster.security.TenantContext;
import com.moveinsync.rostersync.roster.service.ReconciliationService;
import com.moveinsync.rostersync.roster.service.ReconciliationService.Accepted;
import com.moveinsync.rostersync.roster.service.ReconciliationService.ErrorPage;
import com.moveinsync.rostersync.roster.service.ReconciliationService.Manifest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

/** Nightly snapshot reconciliation: upload (202 + run id to poll), inspection, approval and lifecycle. */
@RestController
@RequestMapping("/api/v1/reconciliations")
public class ReconciliationController {

    private final ReconciliationService reconciliation;
    private final JsonMapper json;

    public ReconciliationController(ReconciliationService reconciliation, JsonMapper json) {
        this.reconciliation = reconciliation;
        this.json = json;
    }

    /** Parts: {@code file} (CSV) and {@code manifest} (JSON {sourceId, cutoffSeq, exportedAt, expectedRows?, sha256?}). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('OPERATOR', 'HR_INTEGRATION')")
    public ResponseEntity<Accepted> upload(@RequestPart("file") MultipartFile file,
                                           @RequestPart("manifest") String manifest) throws IOException {
        Manifest parsed = json.readValue(manifest, Manifest.class);
        return ResponseEntity.accepted().body(reconciliation.upload(TenantContext.current(), file.getInputStream(), parsed));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public RunView get(@PathVariable UUID id) {
        return RunView.from(reconciliation.get(TenantContext.current(), id), json);
    }

    @GetMapping("/{id}/errors")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ErrorPage errors(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "50") int size) {
        return reconciliation.errors(TenantContext.current(), id, page, size);
    }

    @GetMapping("/pending-approval")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public List<RunView> pendingApproval() {
        return reconciliation.pendingApproval(TenantContext.current()).stream().map(r -> RunView.from(r, json)).toList();
    }

    public record ApproveRequest(String assessmentHash, String reason) {
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Accepted> approve(@PathVariable UUID id, @RequestBody ApproveRequest request) {
        return ResponseEntity.accepted().body(reconciliation.approve(TenantContext.current(), id, request.assessmentHash(),
                request.reason(), TenantContext.principal()));
    }

    public record RejectRequest(String reason) {
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public Accepted reject(@PathVariable UUID id, @RequestBody(required = false) RejectRequest request) {
        return reconciliation.reject(TenantContext.current(), id, TenantContext.principal());
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Accepted> retry(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(reconciliation.retry(TenantContext.current(), id));
    }
}
