# P23 Automation Mapping

Status: `VERIFIED` by `P23-E001`—`P23-E007`.

This mapping is derived only from the frozen textual requirements, architecture/domain documents, UI main contract, token JSON, screen YAML and requirement CSV. The excluded PNG/HTML visual drafts were not opened, parsed, measured, sampled, hashed or used as screenshot baselines.

## Requirements and invariants

| Contract | Production realization | Verification |
|---|---|---|
| REQ-004 | `TransactionBlueprint` plus deterministic `RecurrenceSeries` are the only automation inputs. No notification, SMS, bank or payment-platform reader/permission exists. | P23-E001, P23-E006, P23-E007 |
| REQ-039 | Formal credit/loan occurrence generation calls the P19/P21 typed ports and preserves their eligibility/idempotency rules. Every UI disclosure says this is an app bookkeeping record, never proof that a bank paid. | P23-E001, P23-E003, P23-E004 |
| REQ-058 | Blueprint revisions exclude actual occurrence time, captured/current location, attachments and live FX. REC-026 stays in `:feature:record`; selecting a template opens the complete REC-003 form without saving. | P23-E001, P23-E004 |
| REQ-059 | The pure engine covers all closed recurrence variants, explicit zone/weekend/missing-day behavior, ten-occurrence preview, exceptions, three edit scopes, candidate/formal generation and catch-up/retry. | P23-E001—P23-E004 |
| INV-027 | The occurrence unique key `UNIQUE(series_id, series_revision_id, occurrence_instant)` plus a deterministic occurrence ID makes startup, Worker, restart and retry replay-safe. Unique WorkManager names use `KEEP`. | P23-E002, P23-E003 |
| INV-028 | Candidate creation inserts only occurrence/candidate operation rows. Confirmation writes a normal user transaction and consumes the candidate link after the financial facts, in the same `FinancialMutationCoordinator` transaction. | P23-E002, P23-E003 |

## Typed lifecycle and persistence mapping

| Frozen model/table family | Implementation |
|---|---|
| `TransactionBlueprint` / `transaction_blueprint` | Current identity, status/name/icon/color and current revision pointer in `SecureRoomAutomationApplicationPort`. |
| `TransactionBlueprintRevision` / `transaction_blueprint_revision` | Immutable transaction kind and typed category/account/card/merchant/project/goal/settlement/fixed-place references; optional amount; no runtime-only fields. |
| `RecurrenceSeries` / `recurrence_series` | Current status, blueprint identity and current revision pointer. |
| `RecurrenceSeriesRevision` / `recurrence_series_revision` | Frequency, interval, start/end/count, explicit zone, missing-day/weekend policies, formal/candidate mode and notification setting. |
| `recurrence_rule_weekday` | Closed weekday set for weekly rules. |
| `RecurrenceException` / `recurrence_exception` | Skip, move or frozen blueprint-revision override by local occurrence date. |
| `RecurrenceOccurrence` / `recurrence_occurrence` | Immutable scheduled instant/local date/revision identity and audited pending/candidate/transaction/failed/skipped/cancelled state. |
| `RecurrenceCandidate` / `recurrence_candidate` | Pending/invalid/accepted/rejected operation lifecycle; never Journal, Posting or Effect authority. |

All blueprint and series changes append `book_commit`, immutable revision and `entity_revision`/`entity_change` audit records. `THIS_OCCURRENCE` appends an exception; `THIS_AND_FUTURE` and `ENTIRE_SERIES` append a new series revision and retain old revisions. No financial DAO is exposed to a feature or Worker.

## Deterministic rule closure

`RecurrenceEngine` is pure Kotlin and accepts the through-instant and existing occurrence instants explicitly. It implements daily, business-day, weekly weekday sets, day-of-month, last day, nth weekday, every-N-months, yearly and custom day interval rules. Zone, local time, weekend movement, missing-date policy, end date, maximum count, exception and ten-item preview behavior are deterministic and have no clock, Android, network, Room or floating-point dependency.

Schema v1 has no occurrence-time column. P23 therefore uses the documented fixed 09:00 local generation time for persisted series while retaining the typed `LocalTime` boundary and explicit `zone_id`; see DL-103. Historical occurrence rows always retain their exact instant and series revision.

## Catch-up, formal generation and failure behavior

- Session-ready startup performs immediate catch-up, then enqueues unique one-time and 12-hour periodic WorkManager jobs.
- The Worker has no direct automation/data port: `AppHeadlessRecurrenceExecutor` must acquire and release a `HeadlessBookLease` with `RECURRENCE_WRITE` capability before the encrypted adapter can run.
- Worker input is exactly one opaque `operationId` (the book stable identity). Full rules and business values remain in the encrypted primary database.
- Reservation happens transactionally before generation. Retry reuses the unique occurrence; it cannot create a second business transaction or candidate.
- Candidate mode and amount-empty templates create candidates. Formal ordinary entries call the ordinary application port; official credit statements and exact loan due components call the P19/P21 application ports.
- Ineligible or failed formal generation creates an `INVALID` candidate with a sanitized domain code. It never reports fake success and never claims a real payment occurred.
- Fixed place comes only from the frozen blueprint revision. Periodic execution never requests current location and never carries attachments or a live FX quote.

## Route and screen closure

All routes are generated by the P04 contract and carry only `StableId` parameters (`templateId`, `seriesId`, `candidateId`) where the YAML permits them. No amount, name, note, account/card detail, attachment, coordinate, full object or candidate draft enters route/SavedState.

| Screen | Frozen module | Required states | Production surface |
|---|---|---|---|
| REC-026 | `:feature:record` | content, empty | Searchable template cards opening the complete editable record form. |
| AUT-001 | `:feature:automation` | content | Hub, candidate count and truthful automation disclosure. |
| AUT-002 | `:feature:automation` | content, empty | Template list/add. |
| AUT-003 | `:feature:automation` | create, edit, validationError | Versioned template editor and unsupported runtime-field notice. |
| AUT-004 | `:feature:automation` | content, empty, paused | Series list/status. |
| AUT-005 | `:feature:automation` | create, edit, invalid | Series editor, mode and fixed-place contract. |
| AUT-006 | `:feature:automation` | editing, invalid | Closed recurrence rule controls. |
| AUT-007 | `:feature:automation` | content, empty | Next ten occurrences and zone disclosure. |
| AUT-008 | `:feature:automation` | content, empty, selection | Fact-free candidate list/selection. |
| AUT-009 | `:feature:automation` | editing, validationError, invalidSource | Explicit “not posted” state, full-form confirmation and skip. |
| AUT-010 | `:feature:automation` | content | Only-this / this-and-future / entire-series scope. |

The 11 screens and exactly 25 required states are verified across 320/360/480dp, 100/130/200% font, zh-CN/ja-JP/en-US and light/dark boundaries. AUT-001/AUT-008 production-pixel goldens are generated from Compose plus frozen text/token/YAML inputs only.

## Phase boundary

P23 is `VERIFIED`. It does not promote P24 or later work. Notification delivery, broader durable operation UI, analytics, widgets, import/backup and final release acceptance remain with their owning stages.
