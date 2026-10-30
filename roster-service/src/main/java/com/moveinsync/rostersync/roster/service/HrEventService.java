package com.moveinsync.rostersync.roster.service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import com.moveinsync.rostersync.contract.EmployeeChangedEvent;
import com.moveinsync.rostersync.contract.EventType;
import com.moveinsync.rostersync.roster.cache.OfficeCatalog;
import com.moveinsync.rostersync.roster.core.Bucketer;
import com.moveinsync.rostersync.roster.core.CanonicalHasher;
import com.moveinsync.rostersync.roster.core.Decision;
import com.moveinsync.rostersync.roster.core.EmployeeState;
import com.moveinsync.rostersync.roster.core.FieldError;
import com.moveinsync.rostersync.roster.core.HrEventKind;
import com.moveinsync.rostersync.roster.core.HrEventRequest;
import com.moveinsync.rostersync.roster.core.HrRecord;
import com.moveinsync.rostersync.roster.core.Incoming;
import com.moveinsync.rostersync.roster.core.OwnershipPolicy;
import com.moveinsync.rostersync.roster.core.RecordValidator;
import com.moveinsync.rostersync.roster.core.VersionPolicy;
import com.moveinsync.rostersync.roster.repository.EmployeeRepository;
import com.moveinsync.rostersync.roster.repository.EmployeeRow;
import com.moveinsync.rostersync.roster.repository.HistoryRepository;
import com.moveinsync.rostersync.roster.repository.IngestEventRepository;
import com.moveinsync.rostersync.roster.repository.LockRepository;
import com.moveinsync.rostersync.roster.repository.OutboxRepository;
import com.moveinsync.rostersync.roster.repository.RecordErrorRepository;
import com.moveinsync.rostersync.roster.repository.TenantSourceRepository;
import com.moveinsync.rostersync.roster.repository.TenantTx;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Versioned ingestion of one HR event (spec §8). Validation happens first, outside the main
 * transaction. The main transaction takes the tenant lock (shared) and the per-employee lock, records
 * event identity, reads the stored state, asks {@link VersionPolicy} and, on APPLY, writes the
 * employee, its history and its outbox row together.
 */
@Service
public class HrEventService {

    private final TenantTx tenantTx;
    private final LockRepository locks;
    private final TenantSourceRepository tenantSources;
    private final IngestEventRepository ingestEvents;
    private final EmployeeRepository employees;
    private final HistoryRepository history;
    private final OutboxRepository outbox;
    private final RecordErrorRepository recordErrors;
    private final OfficeCatalog officeCatalog;
    private final RejectionRecorder rejections;
    private final String sourceId;
    private final RecordValidator validator = RecordValidator.forApi();

    public HrEventService(TenantTx tenantTx, LockRepository locks, TenantSourceRepository tenantSources,
                          IngestEventRepository ingestEvents, EmployeeRepository employees, HistoryRepository history,
                          OutboxRepository outbox, RecordErrorRepository recordErrors, OfficeCatalog officeCatalog,
                          RejectionRecorder rejections, @Value("${app.tenant-source-default}") String sourceId) {
        this.tenantTx = tenantTx;
        this.locks = locks;
        this.tenantSources = tenantSources;
        this.ingestEvents = ingestEvents;
        this.employees = employees;
        this.history = history;
        this.outbox = outbox;
        this.recordErrors = recordErrors;
        this.officeCatalog = officeCatalog;
        this.rejections = rejections;
        this.sourceId = sourceId;
    }

    public IngestResult ingest(String tenantId, HrEventRequest request) {
        Set<String> offices = officeCatalog.activeOffices(tenantId);
        List<FieldError> errors = validator.validate(request, offices::contains);
        if (!errors.isEmpty()) {
            return rejections.reject(tenantId, sourceId, request, errors);
        }
        long lockKey = tenantSources.lockKey(tenantId, sourceId).orElseThrow(NotFoundException::new);
        try {
            return tenantTx.inTenant(tenantId, status -> applyLocked(tenantId, lockKey, request));
        } catch (PessimisticLockingFailureException e) {
            throw new ReconciliationInProgressException();
        }
    }

    /** Records a line that could not be parsed at all (batch path). */
    public IngestResult rejectUnparseable(String tenantId, FieldError error) {
        return rejections.reject(tenantId, sourceId, null, List.of(error));
    }

    private IngestResult applyLocked(String tenantId, long lockKey, HrEventRequest r) {
        String employeeId = r.employeeId();
        locks.lockTimeout3s();
        locks.tenantShared(lockKey);
        locks.employee(tenantId, sourceId, employeeId);

        byte[] payloadHash = CanonicalHasher.payloadHash(r);
        OptionalLong ingestId = ingestEvents.insertIfAbsent(tenantId, sourceId, r.eventId(), payloadHash, employeeId,
                r.sourceSeq(), null, null);
        if (ingestId.isEmpty()) {
            if (Arrays.equals(ingestEvents.payloadHash(tenantId, sourceId, r.eventId()), payloadHash)) {
                return IngestResult.of(r.eventId(), employeeId, IngestOutcome.DUPLICATE, null);
            }
            recordErrors.insertForEvent(tenantId, r.eventId(), null, employeeId, "eventId", "EVENT_ID_REUSED");
            return IngestResult.conflict(r.eventId(), employeeId, "eventId", "EVENT_ID_REUSED");
        }
        long ingestRow = ingestId.getAsLong();

        Optional<EmployeeRow> row = employees.findForUpdate(tenantId, sourceId, employeeId);
        Long tombstoneSeq = row.isPresent() ? null : employees.tombstoneSeq(tenantId, sourceId, employeeId).orElse(null);
        long watermark = tenantSources.absenceWatermark(tenantId, sourceId);
        HrRecord hr = CanonicalHasher.normalize(r.hr());
        byte[] hrHash = r.eventType() == HrEventKind.UPSERT ? CanonicalHasher.hrHash(employeeId, hr) : null;

        Decision decision = VersionPolicy.decide(
                row.map(e -> new EmployeeState(e.active(), e.sourceSeq(), e.hrHash())).orElse(null),
                tombstoneSeq, new Incoming(r.eventType(), r.sourceSeq(), hrHash), watermark);
        Long currentRevision = row.map(EmployeeRow::aggregateRevision).orElse(null);

        return switch (decision.outcome()) {
            case APPLY -> apply(tenantId, ingestRow, r, row, hr, hrHash, decision.eventType());
            case TOMBSTONE -> {
                employees.upsertTombstone(tenantId, sourceId, employeeId, r.sourceSeq());
                ingestEvents.setOutcome(ingestRow, IngestOutcome.TOMBSTONED.name(), null);
                yield IngestResult.of(r.eventId(), employeeId, IngestOutcome.TOMBSTONED, null);
            }
            case STALE -> {
                ingestEvents.setOutcome(ingestRow, IngestOutcome.STALE.name(), null);
                yield IngestResult.of(r.eventId(), employeeId, IngestOutcome.STALE, currentRevision);
            }
            case DUPLICATE -> {
                ingestEvents.setOutcome(ingestRow, IngestOutcome.DUPLICATE.name(), null);
                yield IngestResult.of(r.eventId(), employeeId, IngestOutcome.DUPLICATE, currentRevision);
            }
            case CONFLICT -> {
                ingestEvents.setOutcome(ingestRow, IngestOutcome.CONFLICT.name(), List.of("VERSION_CONFLICT"));
                recordErrors.insertForEvent(tenantId, r.eventId(), null, employeeId, "sourceSeq", "VERSION_CONFLICT");
                yield IngestResult.conflict(r.eventId(), employeeId, "sourceSeq", "VERSION_CONFLICT");
            }
        };
    }

    private IngestResult apply(String tenantId, long ingestRow, HrEventRequest r, Optional<EmployeeRow> row,
                               HrRecord hr, byte[] hrHash, EventType type) {
        String employeeId = r.employeeId();
        long seq = r.sourceSeq();
        long revision;
        boolean active;
        List<String> changed;
        if (row.isEmpty()) {
            employees.insert(tenantId, sourceId, employeeId, hr, seq, hrHash, Bucketer.bucket(employeeId));
            employees.deleteTombstone(tenantId, sourceId, employeeId);
            revision = 1;
            active = true;
            changed = OwnershipPolicy.changedFields(null, hr, true, false);
        } else if (r.eventType() == HrEventKind.UPSERT) {
            EmployeeRow current = row.get();
            revision = employees.applyHrUpsert(tenantId, sourceId, employeeId, hr, seq, hrHash, Bucketer.bucket(employeeId))
                    .orElseThrow(() -> new IllegalStateException("guarded update matched no row under the row lock"));
            active = true;
            changed = OwnershipPolicy.changedFields(current.hr(), hr, !current.active(), current.pickupPoint() != null);
        } else {
            EmployeeRow current = row.get();
            revision = employees.applyHrDeactivate(tenantId, sourceId, employeeId, seq)
                    .orElseThrow(() -> new IllegalStateException("guarded update matched no row under the row lock"));
            active = false;
            changed = OwnershipPolicy.changedFields(null, null, current.active(), false);
        }
        history.insert(tenantId, employeeId, seq, revision, type.name(), "API", null);
        outbox.insert(new EmployeeChangedEvent(UUID.randomUUID(), type, tenantId, employeeId, revision, active,
                r.effectiveAt(), changed));
        ingestEvents.setOutcome(ingestRow, IngestOutcome.APPLIED.name(), null);
        return IngestResult.of(r.eventId(), employeeId, IngestOutcome.APPLIED, revision);
    }
}
