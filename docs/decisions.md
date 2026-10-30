# Decisions

These decisions describe the implemented services. Each records the context, the decision, the rejected alternative and the consequence.

## DEC-01 — Full HR state

**Context:** Delayed updates must be safe to ignore. **Decision:** Events carry all HR-owned fields. **Rejected alternative:** Patch events. **Consequence:** Patch feeds require field-level versioning.

## DEC-02 — Source ordering

**Context:** Timestamps can tie or skew. **Decision:** A tenant-wide monotonic sourceSeq orders HR changes and export cutoffs. **Rejected alternative:** Timestamp last-write-wins. **Consequence:** The source must provide comparable versions; gaps are allowed.

## DEC-03 — Versioned deactivation

**Context:** A delayed create must not resurrect a leaver. **Decision:** Persist deactivation versions, including tombstones for unknown IDs. **Rejected alternative:** Physical deletion. **Consequence:** Reactivation requires a higher HR version.

## DEC-04 — Snapshot cutoff

**Context:** Live changes can arrive after export. **Decision:** Only active rows at or below cutoff S can be inferred absent. **Rejected alternative:** Deactivate every ID missing from the file. **Consequence:** Newer API changes survive; inferred deactivations carry S.

## DEC-05 — Exact safety brake

**Context:** The threshold boundary must be deterministic. **Decision:** Compare integer basis points; default 3000, and baseline must be positive. **Rejected alternative:** Floating-point ratios. **Consequence:** Exactly 30% missing blocks the complete apply.

## DEC-06 — Transport ownership on rehire

**Context:** HR does not own transport arrangements. **Decision:** Keep transport columns across HR updates and reactivation. **Rejected alternative:** Reset admin fields on rehire. **Consequence:** An admin must deliberately change them.

## DEC-07 — Office transfer review

**Context:** An old pickup may be unsuitable after transfer. **Decision:** Preserve pickup and emit PICKUP_REVIEW_NEEDED. **Rejected alternative:** Silently clear or ignore it. **Consequence:** Consumers can request admin review without losing data.

## DEC-08 — Invalid evidence

**Context:** A bad row is not proof that an employee left. **Decision:** Invalid, unidentifiable or conflicting rows suppress all inferred absences. **Rejected alternative:** Treat invalid rows as missing. **Consequence:** Valid rows can apply in PARTIAL_NO_DEACTIVATIONS.

## DEC-09 — Manifest mismatch

**Context:** A cut file cannot be repaired by approval. **Decision:** Row-count mismatch prevents approval; checksum/structure failure is INCOMPLETE. **Rejected alternative:** Approve incomplete evidence. **Consequence:** Correct and upload again.

## DEC-10 — Atomic snapshot apply

**Context:** Partial live state and partial outbox would be unsafe. **Decision:** Assess and apply in one bulk transaction under an exclusive tenant/source lock. **Rejected alternative:** Chunked live commits. **Consequence:** Tenant writes may wait or receive retryable 503; lock cost is measured.

## DEC-11 — HTTP outbox delivery

**Context:** The assessment needs real process boundaries. **Decision:** Push over HTTP with at-least-once retries and atomic consumer dedup. **Rejected alternative:** A broker deployment. **Consequence:** A crash can duplicate delivery but cannot duplicate effects.

## DEC-12 — Per-employee delivery order

**Context:** Reactivation must not overtake deactivation. **Decision:** Earlier undelivered revisions block later ones, including DEAD predecessors. **Rejected alternative:** Unordered queue claiming. **Consequence:** Operator replay unblocks that employee; others continue.

## DEC-13 — Future bookings

**Context:** Trips already underway must remain intact. **Decision:** Cancel RESERVED bookings whose scheduled start is after the event effectiveAt. **Rejected alternative:** Cancel all bookings. **Consequence:** Past and in-progress trips are preserved.

## DEC-14 — Reactivation and bookings

**Context:** Restoring cancelled seats would recreate side effects. **Decision:** Reactivation updates the projection without restoring bookings. **Rejected alternative:** Automatically reinstate bookings. **Consequence:** New bookings require a new request.

## DEC-15 — Equal-version conflict

**Context:** One version cannot represent two truths. **Decision:** Equal sequence with different intent or canonical HR hash is a conflict. **Rejected alternative:** Arrival-order overwrite. **Consequence:** The source must resolve ambiguity.

## DEC-16 — Development issuer

**Context:** A demo needs reproducible role tokens. **Decision:** Use the configurable demo issuer and persistent RS256 key; validate issuer, audience and expiry in both services. **Rejected alternative:** An external identity-provider deployment for the demo. **Consequence:** Production needs its own identity and secrets setup; disable the dev issuer.

## DEC-17 — Admin deactivation version

**Context:** HR owns sourceSeq. **Decision:** Admin deactivation increments aggregateRevision and leaves sourceSeq unchanged. **Rejected alternative:** Invent an HR sequence. **Consequence:** A later higher HR UPSERT can reactivate; same-version snapshot evidence is a no-op.

## DEC-18 — Seen IDs in staging

**Context:** Duplicate snapshot IDs need bounded tracking. **Decision:** Keep a seen-ID set and stage in 5,000-row COPY chunks. **Rejected alternative:** Load both full employee datasets in Java. **Consequence:** Seen-ID memory grows with export IDs, roughly 15 MB for 200k; the whole JVM is benchmarked at 256 MiB.

## DEC-19 — Reconciliation changed fields

**Context:** Downstream needs useful change names without PII. **Decision:** Compute detailed field names from PostgreSQL 18 RETURNING old/new values. **Rejected alternative:** A generic hr marker. **Consequence:** One CTE writes the state, history and PII-free outbox together.

## DEC-20 — Unknown employee tombstones

**Context:** A deactivation can arrive before creation. **Decision:** Persist the version floor without fabricating an HR profile or outbox event. **Rejected alternative:** Drop the deactivation. **Consequence:** Observable inactive state is order-independent; an unknown leaver’s profile is not retained.

## DEC-21 — Bucket and version digest

**Context:** A typical night must leave useful buckets unchanged. **Decision:** Use 65,536 SHA-256 buckets; include employee ID, canonical hash and sequence in each digest. **Rejected alternative:** 1,024 buckets or a hash without the version. **Consequence:** Quiet changes touch few buckets; version-only advances remain detectable.

## DEC-22 — Bulk connection pool

**Context:** API outage timeouts would kill legitimate reconciliation work. **Decision:** Use separate pools with 10s/330s socket timeouts and 5s/300s statement timeouts. **Rejected alternative:** One timeout policy for both workloads. **Consequence:** Both pools retain the same restricted app role and tenant transaction semantics.

## DEC-23 — Same-cutoff lineage

**Context:** Replay must be safe while corrected failed files remain possible. **Decision:** Compare against COMPLETED runs: equal checksum is a no-op, different checksum conflicts; allow resend after partial/failed runs if no completed truth exists. **Rejected alternative:** Let the latest failed conflict redefine the cutoff. **Consequence:** Repeated conflicting uploads remain conflicts, even after an earlier rejection.
