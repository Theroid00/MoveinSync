package com.moveinsync.rostersync.roster.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.moveinsync.rostersync.contract.EmployeeChangedEvent;
import com.moveinsync.rostersync.contract.ErrorCode;
import com.moveinsync.rostersync.contract.EventType;
import com.moveinsync.rostersync.roster.core.FieldError;
import com.moveinsync.rostersync.roster.repository.EmployeeRepository;
import com.moveinsync.rostersync.roster.repository.EmployeeRow;
import com.moveinsync.rostersync.roster.repository.HistoryRepository;
import com.moveinsync.rostersync.roster.repository.HistoryRepository.HistoryEntry;
import com.moveinsync.rostersync.roster.repository.IngestEventRepository;
import com.moveinsync.rostersync.roster.repository.IngestEventRepository.IngestEventView;
import com.moveinsync.rostersync.roster.repository.LockRepository;
import com.moveinsync.rostersync.roster.repository.OutboxRepository;
import com.moveinsync.rostersync.roster.repository.TenantSourceRepository;
import com.moveinsync.rostersync.roster.repository.TenantTx;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

/** Reads plus the two non-HR writes: transport-admin edits and admin deactivation. */
@Service
public class EmployeeService {

    static final int MAX_TRANSPORT_LENGTH = 500;

    private final TenantTx tenantTx;
    private final LockRepository locks;
    private final TenantSourceRepository tenantSources;
    private final EmployeeRepository employees;
    private final HistoryRepository history;
    private final IngestEventRepository ingestEvents;
    private final OutboxRepository outbox;
    private final String sourceId;

    public EmployeeService(TenantTx tenantTx, LockRepository locks, TenantSourceRepository tenantSources,
                           EmployeeRepository employees, HistoryRepository history, IngestEventRepository ingestEvents,
                           OutboxRepository outbox, @Value("${app.tenant-source-default}") String sourceId) {
        this.tenantTx = tenantTx;
        this.locks = locks;
        this.tenantSources = tenantSources;
        this.employees = employees;
        this.history = history;
        this.ingestEvents = ingestEvents;
        this.outbox = outbox;
        this.sourceId = sourceId;
    }

    public record EmployeeHistory(String employeeId, List<HistoryEntry> changes, List<IngestEventView> events) {
    }

    public EmployeeRow get(String tenantId, String employeeId) {
        return tenantTx.inTenant(tenantId, s -> employees.find(tenantId, sourceId, employeeId))
                .orElseThrow(NotFoundException::new);
    }

    public EmployeeHistory history(String tenantId, String employeeId) {
        EmployeeHistory result = tenantTx.inTenant(tenantId, s -> new EmployeeHistory(employeeId,
                history.forEmployee(tenantId, employeeId), ingestEvents.forEmployee(tenantId, employeeId)));
        if (result.changes().isEmpty() && result.events().isEmpty()) {
            throw new NotFoundException();
        }
        return result;
    }

    /** Transport-owned fields only; replaces both values. Returns the new aggregate revision. */
    public long updateTransport(String tenantId, String employeeId, long ifMatch, String pickupPoint,
                                String specialAssistance) {
        List<FieldError> errors = new ArrayList<>();
        if (pickupPoint != null && pickupPoint.length() > MAX_TRANSPORT_LENGTH) {
            errors.add(new FieldError("pickupPoint", "TOO_LONG"));
        }
        if (specialAssistance != null && specialAssistance.length() > MAX_TRANSPORT_LENGTH) {
            errors.add(new FieldError("specialAssistance", "TOO_LONG"));
        }
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }
        return locked(tenantId, employeeId, () -> {
            EmployeeRow before = employees.findForUpdate(tenantId, sourceId, employeeId).orElseThrow(NotFoundException::new);
            long revision = employees.applyTransport(tenantId, sourceId, employeeId, pickupPoint, specialAssistance, ifMatch)
                    .orElseThrow(() -> new ConflictException(ErrorCode.REVISION_MISMATCH));
            List<String> changed = new ArrayList<>();
            if (!Objects.equals(before.pickupPoint(), pickupPoint)) {
                changed.add("pickupPoint");
            }
            if (!Objects.equals(before.specialAssistance(), specialAssistance)) {
                changed.add("specialAssistance");
            }
            emit(tenantId, employeeId, before.sourceSeq(), revision, EventType.EmployeeTransportUpdated,
                    before.active(), changed);
            return revision;
        });
    }

    /** Admin deactivation (DEC-17): source_seq untouched; a later, higher HR seq reactivates. */
    public long deactivate(String tenantId, String employeeId) {
        return locked(tenantId, employeeId, () -> {
            EmployeeRow before = employees.findForUpdate(tenantId, sourceId, employeeId).orElseThrow(NotFoundException::new);
            long revision = employees.applyAdminDeactivate(tenantId, sourceId, employeeId)
                    .orElseThrow(() -> new ConflictException(ErrorCode.ALREADY_INACTIVE));
            emit(tenantId, employeeId, before.sourceSeq(), revision, EventType.EmployeeDeactivated, false, List.of("active"));
            return revision;
        });
    }

    private void emit(String tenantId, String employeeId, long sourceSeq, long revision, EventType type, boolean active,
                      List<String> changed) {
        history.insert(tenantId, employeeId, sourceSeq, revision, type.name(), "ADMIN", null);
        outbox.insert(new EmployeeChangedEvent(UUID.randomUUID(), type, tenantId, employeeId, revision, active,
                Instant.now(), changed));
    }

    private long locked(String tenantId, String employeeId, java.util.function.LongSupplier work) {
        long lockKey = tenantSources.lockKey(tenantId, sourceId).orElseThrow(NotFoundException::new);
        try {
            return tenantTx.inTenant(tenantId, s -> {
                locks.lockTimeout3s();
                locks.tenantShared(lockKey);
                locks.employee(tenantId, sourceId, employeeId);
                return work.getAsLong();
            });
        } catch (PessimisticLockingFailureException e) {
            throw new ReconciliationInProgressException();
        }
    }
}
