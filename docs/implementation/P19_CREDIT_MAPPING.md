# P19 Credit Account Mapping

P19 is `VERIFIED`. This mapping covers credit profiles, statement calendars and limits, estimated/official statements, immutable assignment and repayment/allocation facts, automatic bookkeeping eligibility, synchronous credit projections, and REC-014 plus CRD-001—CRD-008. It does not promote installment plans, loan schedules, analytics, widgets or the P23 occurrence-engine scheduler.

## Frozen model to implementation

| Frozen surface | Production implementation | Evidence |
|---|---|---|
| `CreditAccountProfile` | `CreditCalendarPolicy`, typed `SaveCreditProfileRequest` and revisioned Room adapter preserve billing day, due rule, time zone, permanent/temporary limit, default payment account and auto-generation mode | `P19-E001`—`P19-E003` |
| Calendar and due date | Pure deterministic policy resolves missing month days, skipped local dates and adjusted due days in the account zone; no device-current zone or wall clock participates | `P19-E002` |
| Estimated statement | A deterministic statement shell and immutable revision are created atomically from transaction assignment evidence; amount remains rebuildable from statement effects | `P19-E002`, `P19-E003` |
| Official statement | `SaveCreditStatementRequest` appends an official amount/seal revision under expected-revision control; official-versus-estimated difference is query/UI evidence only | `P19-E001`, `P19-E003`—`P19-E005` |
| Statement status | Closed estimated/official/sealed/overdue/paid status policy uses effective date, due date, official amount and allocated repayment; no minimum-payment field or calculation exists | `P19-E001`—`P19-E004` |
| Consumption accounting | Credit consumption increases the complete credit liability and emits the real expense effect; it never treats borrowing as income | `P19-E002`, `P19-E003` |
| Repayment accounting | `RecordCreditPaymentCommand` generates balanced bank-asset/credit-liability postings and `StatementEffect` allocation with no EconomicEffect | `P19-E002`, `P19-E003` |
| Payment allocation | `CreditPaymentAllocationPolicy` supports earliest-first, one specified statement and explicit unallocated advance; immutable allocation APPLY/REVERSE facts make reassignment rebuildable | `P19-E002`, `P19-E003` |
| Overpayment | The encrypted snapshot's actual active debt is authoritative. Every active overpayment is rejected before commit; a passive positive balance remains queryable, explained and included in available limit | `P19-E002`—`P19-E004` |
| Statement assignment | Automatic cycle assignment and manual previous/current/next selection live in typed transaction revision state; sealed-statement changes append history and surface an impact warning | `P19-E001`, `P19-E003`, `P19-E004` |
| Automatic bookkeeping | `CreditAutoPaymentPolicy` requires enabled formal mode, a due unpaid statement, valid default payment account, sufficient actual debt and a unique occurrence; an exact retry returns the original receipt | `P19-E002`, `P19-E003` |
| Candidate mode | Candidate evaluation returns a proposal only. It never calls the financial coordinator and creates no transaction, Journal, Posting, effect, allocation, receipt or book revision | `P19-E002`, `P19-E003` |

## Lifecycle and schema mapping

| Lifecycle | P19 credit representation |
|---|---|
| Current | `credit_account_profile`, `credit_statement`, current transaction/revision pointers and typed application views |
| Revision | `credit_statement_revision` plus transaction revision and statement-assignment snapshot; old rows are never overwritten |
| Fact | Journal/Posting for consumption or repayment, `statement_effect`, and immutable `credit_payment_allocation` APPLY/REVERSE rows |
| Projection | `credit_account_projection` and `credit_statement_projection`, versioned at the committed `book.localRevision` and rebuilt from authoritative current/revision/fact rows |
| Cache | No network or bank value is authoritative; P19 adds no credit cache and no historical fact depends on mutable external data |
| Operation | Formal automatic bookkeeping is identified by its stable occurrence key and command receipt; candidate evaluation is not an operation or fact |

The frozen Schema v1 already contains the full credit family: `credit_account_profile`, `credit_limit_period`, `credit_statement`, `credit_statement_revision`, `credit_payment_revision_detail`, `credit_payment_allocation`, `statement_effect`, and both credit projections. P19 therefore needs no migration, JSON payload, side database or plaintext persistence path.

## Write and query boundary

Every credit consumption, repayment, repayment reallocation and statement-assignment financial change reaches immutable facts only through `FinancialMutationCoordinator`. `SecureRoomCreditApplicationPort` constructs typed commands and encrypted reference snapshots; `RoomCreditPlanWriter` is invoked only by the normalized financial repository transaction. Feature/UI code has no DAO, Entity, SQL, Journal or Posting capability.

The encrypted commit rechecks `expectedRevisionId`, command hash and actual debt, writes the revision/facts/effects/allocation/current pointer/receipt/projections/book revision atomically, and returns the original receipt for an identical command retry. Injected conflicts and failures leave no partial statement, allocation, fact, projection or revision state. Rebuild reproduces both credit projections and their canonical result.

Credit routes carry only `StableId` account, statement or transaction identifiers. Amounts, limits, official values, card details, names, notes, attachments, locations and complete objects remain outside routes and SavedState. The root destination remains behind SessionGate and derives its state from the encrypted application snapshot.

## Automatic repayment boundary

Formal mode records bookkeeping facts only after all five eligibility facts pass and is idempotent per occurrence. It does not initiate, observe or claim a real bank payment. Candidate mode emits no facts and is visibly labeled as a candidate. P23 may call this same typed proposal/record boundary from its occurrence engine; it may not bypass eligibility, reuse UI state as authority or change the disclaimer.

Official and estimated statement differences are likewise display-only. They never create a balance adjustment, and the application intentionally has no minimum-payment field because the frozen requirement prohibits calculating one.

## UI and accessibility coverage

The exact 29 required states across REC-014 and CRD-001—008 render against the YAML oracle. The device matrix covers 320/360/480dp, 100/130/200% font scale, zh-CN/ja-JP/en-US and light/dark boundaries. It exercises active-overpayment blocking, passive positive balance, no-limit/no-statement states, official difference, sealed warning, balanced/mismatched allocation, candidate/ineligible/eligible automation and a fixed reachable save action with stable governed tags.

Two 360×720 Compose full-pixel SHA-256 baselines cover the credit-account light state and official-difference dark state. They are generated only from production Compose, frozen tokens and the textual/YAML/CSV contracts; no excluded visual artifact is an input or oracle.

## Scope boundary

P19 verifies REQ-036—REQ-039 and the nine listed screen contracts. P23 retains occurrence scheduling and background execution integration, but the P19 eligibility/idempotency/accounting policy remains authoritative. Installment business behavior remains P20, loans remain P21, cross-feature analytics remains P25/P34, widgets remain P33, and final performance/release acceptance remains P35/P36.
