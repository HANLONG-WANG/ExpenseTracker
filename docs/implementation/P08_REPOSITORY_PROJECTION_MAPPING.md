# P08 Repository, Projection and Query Mapping

Last updated: 2026-08-02 (Asia/Tokyo)  
Normative scope: architecture §§7—11 and §§19—21; domain atomic-flow, projection and index chapters; REQ-006, REQ-007, REQ-031, REQ-033, REQ-084, REQ-088 and REQ-089.

## Single financial write entry

| Contract | Production implementation | Verification |
|---|---|---|
| UI, Worker and importer submit typed commands only | `FinancialCommandHandler` → `SubmitFinancialCommandUseCase` → `DefaultFinancialMutationCoordinator` | `FINANCE-WRITE-PORT`, `FINANCE-COORDINATOR` and `FINANCE-SQL-WRITE` source rules plus rejection tests (`P08-E002`) |
| Process-local serialization | `DefaultLedgerWriteGate` uses one coroutine `Mutex` | concurrent application test (`P08-E002`) |
| Canonical planning boundary | coordinator verifies command hash, loads a `PlanningSnapshot`, runs the deterministic planner and validates the complete plan before persistence | P06 planner tests retained by `p08Check`; coordinator JVM tests (`P08-E002`, `P08-E004`) |
| Physical privacy purge | ordinary handler and Room repository fail closed with `MaintenanceRequired` | application/device tests; P31 remains the only authorized purge workflow |

## Atomic SQLCipher commit order

`RoomFinancialCommitRepository` runs the following sequence on `LedgerDatabase.inLedgerTransaction`, which is Room's sole SQLCipher connection:

1. Read an existing `CommandReceipt`; return it only when command type and canonical payload hash match.
2. Re-read book state, head, `localRevision`, `valuationRevision` and rule version, then re-check the command's `expectedRevision` against the persisted current pointer.
3. Append commit/parents, transaction shell, frozen FX evidence, Revision/subtype/amount facts, Journal/Posting, subledger facts, every typed Effect and entity changes.
4. Move current transaction pointers, rebuild all P08 synchronous projections and indexes, and audit balance, subtype, projection versions/counts and database integrity.
5. Advance `book.head_commit_id` and `book.local_revision` with a compare-and-set update.
6. Insert `CommandReceipt` last and return the stored mapping.

The five injected checkpoints are after the commit header, after immutable facts, after projections, before book advance and before receipt. A failure at any checkpoint rolls back the entire Room transaction (`P08-E003`).

## Mapper coverage

| Domain output | Normalized tables written |
|---|---|
| Book commit and graph | `book_commit`, `book_commit_parent`, `entity_change`, final `command_receipt` |
| Current/Revision | `business_transaction`, `transaction_revision`, all 11 typed revision-detail tables, attachment/share links, `revision_amount`, `fx_rate_snapshot` |
| Accounting facts | `journal_entry`, `posting` |
| Subledger facts | `refund_allocation`, `credit_payment_allocation`, `loan_actual_allocation`, `goal_movement`, `budget_adjustment`, `blob_gc_candidate` |
| Typed effects | `economic_effect`, `budget_effect`, `project_effect`, `goal_effect`, `statement_effect`, `loan_effect`, `settlement_effect` |

No core field is stored in a generic JSON payload, and no `@Upsert` or destructive fallback is introduced.

## Synchronous projections and versions

| Family | Rebuilt from authoritative normalized state/facts | Version contract |
|---|---|---|
| Current transaction | `current_transaction_projection` | `as_of_local_revision = target book revision` |
| Account | `account_balance_current`, `account_balance_daily` | same local revision; checked integer sums |
| Refund/budget/project/goal | `refund_status_projection`, `budget_usage_projection`, `project_usage_projection`, `goal_balance_projection` | same local revision |
| Credit/installment/loan | `credit_statement_projection`, `credit_account_projection`, `installment_progress_projection`, `loan_progress_projection` | same local revision; date boundaries use explicit commit/book-zone evidence |
| Settlement | `settlement_position_projection` | same local revision |
| Search/geography | `transaction_fts`, `location_rtree`, `place_rtree` | rebuilt in the same transaction; FTS contains only the frozen allowlist |
| Widget snapshots | book/account/credit/goal snapshot tables | same local revision; book snapshot also retains `as_of_valuation_revision` |

Valuation, future-reservation/future-cashflow and analytics rollups belong to later owning stages. P08 neither recomputes nor falsely advances their revisions. This preserves truthful `asOfLocalRevision`/`asOfValuationRevision` boundaries.

`RoomProjectionMaintenanceService.audit` hashes a canonical type- and length-delimited serialization of live projections, rebuilds them inside a savepoint, compares the rebuilt hash, and rolls back the audit copy. `rebuild` enters maintenance, replaces derived state from authoritative facts, verifies versions/integrity and returns to ready state atomically. `startupCheck` performs the lightweight book state, version, unfinished-operation, current-subtype and schema-registry checks.

## Typed query foundation

`RoomTransactionQueryService` compiles `TransactionFilter` into bound SQL, uses `(occurredAt, transactionId)` keyset cursors and never uses offset paging. Text search uses FTS5 only to select candidates while all remaining typed predicates are exact bound filters. Geographic search uses R*Tree bounding candidates and a Kotlin Haversine distance check before returning a result. Page and candidate limits are closed and validated.

## Stage boundary

P08 adds no feature page, Worker, importer runtime, valuation feed, analytics rollup, widget runtime or purge execution. All 215 feature screens remain `NOT_STARTED`; those later capabilities must consume the command/query ports above rather than receive a DAO, Entity, Room connection or atomic repository.
