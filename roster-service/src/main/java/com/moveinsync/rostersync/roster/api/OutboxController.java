package com.moveinsync.rostersync.roster.api;

import org.springframework.security.access.prepost.PreAuthorize;
import com.moveinsync.rostersync.roster.security.TenantContext;
import com.moveinsync.rostersync.roster.service.OutboxAdminService;
import com.moveinsync.rostersync.roster.service.OutboxAdminService.OutboxStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/outbox")
public class OutboxController {

    private final OutboxAdminService outbox;

    public OutboxController(OutboxAdminService outbox) {
        this.outbox = outbox;
    }

    @GetMapping("/status")
    @PreAuthorize("hasRole('OPERATOR')")
    public OutboxStatus status() {
        return outbox.status(TenantContext.current());
    }

    @PostMapping("/{id}/replay")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Void> replay(@PathVariable long id) {
        outbox.replay(TenantContext.current(), id);
        return ResponseEntity.accepted().build();
    }
}
