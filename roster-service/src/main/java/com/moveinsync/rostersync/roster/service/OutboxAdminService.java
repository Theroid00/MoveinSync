package com.moveinsync.rostersync.roster.service;

import java.util.Map;

import com.moveinsync.rostersync.contract.ErrorCode;
import com.moveinsync.rostersync.roster.repository.OutboxRelayRepository;
import org.springframework.stereotype.Service;

/** Operator view of the outbox, always scoped to the caller's tenant. */
@Service
public class OutboxAdminService {

    private final OutboxRelayRepository outbox;

    public OutboxAdminService(OutboxRelayRepository outbox) {
        this.outbox = outbox;
    }

    public record OutboxStatus(Map<String, Long> counts, double oldestPendingAgeSeconds) {
    }

    public OutboxStatus status(String tenantId) {
        return new OutboxStatus(outbox.countsByState(tenantId), outbox.oldestPendingAgeSeconds(tenantId));
    }

    /** DEAD → PENDING with the same eventId; anything else is 409, unknown or foreign ids 404. */
    public void replay(String tenantId, long id) {
        if (!outbox.replay(tenantId, id)) {
            throw outbox.state(tenantId, id).isPresent()
                    ? new ConflictException(ErrorCode.INVALID_RUN_STATE)
                    : new NotFoundException();
        }
    }
}
