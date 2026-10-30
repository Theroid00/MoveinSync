package com.moveinsync.rostersync.booking.consumer;

import java.util.List;
import java.util.UUID;

import com.moveinsync.rostersync.booking.repository.BookingRepository;
import com.moveinsync.rostersync.booking.repository.BookingTenantTx;
import com.moveinsync.rostersync.booking.repository.ProcessedMessageRepository;
import com.moveinsync.rostersync.booking.repository.ProjectionRepository;
import com.moveinsync.rostersync.booking.repository.ProjectionRepository.Projection;
import com.moveinsync.rostersync.contract.ConsumeResult;
import com.moveinsync.rostersync.contract.EmployeeChangedEvent;
import com.moveinsync.rostersync.contract.EventType;
import org.springframework.stereotype.Service;

/**
 * Idempotent consumer (spec §11.1). Dedup, projection and side effects commit in one transaction, so a
 * redelivery after a crash is absorbed by processed_message and has no second effect.
 */
@Service
public class EmployeeEventConsumer {

    private final BookingTenantTx tenantTx;
    private final ProcessedMessageRepository processed;
    private final ProjectionRepository projections;
    private final BookingRepository bookings;

    public EmployeeEventConsumer(BookingTenantTx tenantTx, ProcessedMessageRepository processed,
                                 ProjectionRepository projections, BookingRepository bookings) {
        this.tenantTx = tenantTx;
        this.processed = processed;
        this.projections = projections;
        this.bookings = bookings;
    }

    /** What one delivery did: its outcome and how many bookings it cancelled / seats it released. */
    public record Consumed(String outcome, int cancelled, int seatsReleased) {
        public ConsumeResult result() {
            return new ConsumeResult(outcome);
        }
    }

    public Consumed consume(EmployeeChangedEvent event) {
        return tenantTx.inTenant(event.tenantId(), status -> {
            UUID eventId = event.eventId();
            if (!processed.claim(eventId)) {
                return new Consumed(ConsumeResult.DUPLICATE, 0, 0);
            }
            Projection projection = projections.ensureAndLock(event.tenantId(), event.employeeId());
            if (event.aggregateRevision() <= projection.lastRevision()) {
                processed.complete(eventId, ConsumeResult.SKIPPED_OLDER);
                return new Consumed(ConsumeResult.SKIPPED_OLDER, 0, 0);
            }
            projections.update(event.tenantId(), event.employeeId(), event.active(), event.aggregateRevision(),
                    event.effectiveAt());
            int cancelled = 0;
            int released = 0;
            if (event.eventType() == EventType.EmployeeDeactivated) {
                List<UUID> ids = bookings.cancelFutureReserved(event.tenantId(), event.employeeId(), event.effectiveAt(), eventId);
                cancelled = ids.size();
                released = bookings.releaseSeats(event.tenantId(), ids);
            }
            processed.complete(eventId, ConsumeResult.PROCESSED);
            return new Consumed(ConsumeResult.PROCESSED, cancelled, released);
        });
    }
}
