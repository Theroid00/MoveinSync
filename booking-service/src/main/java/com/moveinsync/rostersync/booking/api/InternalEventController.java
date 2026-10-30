package com.moveinsync.rostersync.booking.api;

import com.moveinsync.rostersync.booking.consumer.EmployeeEventConsumer;
import com.moveinsync.rostersync.booking.service.DomainException;
import com.moveinsync.rostersync.contract.ConsumeResult;
import com.moveinsync.rostersync.contract.EmployeeChangedEvent;
import com.moveinsync.rostersync.contract.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Consumer endpoint called by the roster outbox relay (role SERVICE). Always 200 for a well-formed event. */
@RestController
public class InternalEventController {

    private final EmployeeEventConsumer consumer;

    public InternalEventController(EmployeeEventConsumer consumer) {
        this.consumer = consumer;
    }

    @PostMapping(path = "/internal/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ConsumeResult consume(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                 @RequestBody EmployeeChangedEvent event) {
        if (event.eventId() == null || event.eventType() == null || event.tenantId() == null
                || event.employeeId() == null || event.effectiveAt() == null) {
            throw DomainException.field(ErrorCode.VALIDATION_FAILED, "event", "INCOMPLETE_EVENT");
        }
        if (idempotencyKey != null && !idempotencyKey.equals(event.eventId().toString())) {
            throw DomainException.field(ErrorCode.VALIDATION_FAILED, "Idempotency-Key", "KEY_MISMATCH");
        }
        return consumer.consume(event).result();
    }
}
