# Recoverable rule batches — SNI-64 / SNI-78 / SNI-75

Approved by Thiago on 2026-11-05: Snippets assigns operation IDs, terminal results
remain in Redis seven days, and background format and lint use durable operations.
The provider contract is documented in PrintScript's `docs/durable-operations.md`.

## Workflow and guarantees

The existing handlers select current owned snippets by language/version, with the
shared keyset selector. `ProcessingReaction` separates lint assessment from
formatted-variant publication. `DurableProcessing` owns only remote lifecycle and
capacity: its engine does not branch on individual lint rules or formatting cases.
Language-specific transport stays behind the small `LanguageOperations` capability.

For each item, read stable content and effective owner rules, verify the processor,
then commit a permit and its remote ID/publication basis in ONE PostgreSQL
transaction. Only afterwards send PUT. No HTTP/storage call occurs inside that
reservation transaction. Losing the lease can never let the old turn publish.

A short bounded initial poll avoids an unnecessary reclaim delay for quick work.
If the result is uncertain, the item/job and permit remain UNKNOWN. Recovery
queries the same ID; it never submits a new start while that CPU might be alive.
If GET returns 404, cancellation must establish a durable fence before retrying
with a new ID. Cancellation in progress or a failed connection does not release
capacity. Records are checked for ID, kind, expected processor/version and effective
capacity; results are checked against the persisted source fingerprint and effective
rules hash. A mismatched result cannot be published or settle the permit.

UNKNOWN deliveries may already be acknowledged in Redis. PostgreSQL recovery also
resumes due PENDING/RETRY/RUNNING jobs with existing permit history, so a proven
terminal retry does not depend on another queue delivery.

Legacy synchronous permits without remote identity remain UNKNOWN. They cannot
be made safe by cancelling a new unrelated ID. Drain/independently verify them
before feature activation. New reservations have no unbound-start crash window.

## Publishing

Lint records an assessment and settles progress/permit within the same fenced
transaction. JdbcLintAssessments joins that transaction (or creates one for the
synchronous on-save path). Old content/rules cannot replace the current projection;
old attempts remain internal audit data. Noncompliance is successful analysis,
while technical/invalid-code outcomes remain distinct. List/detail validity,
filter/count/pagination and current-default semantics remain shared.

Format stores a derived blob, then uses the existing compare-and-set publication
for source reference and rules, inside the fenced settlement. A code race retries
only after the old remote computation is known terminal; a rules race supersedes
the old item. Failed storage keeps the remote association so its retained result
can be republished without recomputing. Original content is never overwritten.

The worker shares admission with automatic/manual executions. No TTL/lease age is
used as evidence that remote CPU stopped. The provider additionally reserves a
manual slot and limits background CPU. Its recovery contract supports one
stop-first provider process per namespace, matching the Redis overlay; it is not
multi-provider HA.

## Scope and validation

On-save lint remains synchronous after the confirmed content commit. NDJSON,
manual session privacy, authorization and existing formatted-download behavior
keep their contracts. No VM creation, deployment or CD workflow changes are made.

JDK 21 + Docker: `./gradlew build`. Tests include real PostgreSQL scope/diagnostic
publication, UNKNOWN permit retention, recovery with a single start, superseded
rules, and the existing format/Redis contracts adapted to the durable HTTP API.
Real-provider failure/restart acceptance is recorded separately in SNI-75.
