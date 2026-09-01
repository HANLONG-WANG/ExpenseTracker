# P16 Refund Mapping

Last updated: 2026-08-04 (Asia/Tokyo)

P16 is `VERIFIED`. It implements linked and independent refunds as contra-expense transactions with an immutable refund allocation fact chain, exact cumulative refundable balance, three independent time dimensions, closed excess-risk evidence, synchronous projections, original-transaction dependency policy and REC-015/016 UI. Every refund write reaches `FinancialMutationCoordinator`; neither the feature nor root ViewModel creates a Journal, Posting, effect, DAO write or financial SQL statement.

## Domain, fact and projection map

| Frozen surface | P16 realization | Evidence |
|---|---|---|
| Refund transaction/revision | `RefundPayload` has a receiving account/card, optional expense category, zero-or-more allocations, independent flag, budget/project/goal/accrual policies and optional inverse settlement shares. The closed payload is stored by `refund_revision_detail`; revision/common input fields remain normalized. | `P16-E002`, `P16-E003` |
| Immutable refund allocation | The planner emits `RefundAllocationFact` with source refund transaction/revision, frozen original transaction/revision, original/base minor amounts, commit and optional natural reversal reference. `RoomRefundFactWriter` only appends apply/reversal rows. | `P16-E002`, `P16-E003` |
| Refundable balance | The command snapshot derives the true net allocation total from immutable apply/reversal facts. Default submission fails `INV-010` above the remaining amount; an override is valid only with separate high-risk confirmation evidence. | `P16-E001`—`P16-E004` |
| Accounting meaning | Cash is received and the expense ledger is credited. The economic fact is `CONTRA_EXPENSE`, never `INCOME`; budget/project/goal effects are typed RESTORE facts under independent closed policies. Settlement shares produce inverse typed settlement effects. | `P16-E002`, `P16-E003` |
| Three time dimensions | `occurredAt/localDate` freezes actual cash receipt, `accrualDate` freezes expense attribution, and `budgetTargetMonth` freezes original-month/refund-month/no-restore choice. They are persisted and rebuilt independently. | `P16-E002`, `P16-E003`, `P16-E004` |
| Projection and relationships | `refund_status_projection`, `current_transaction_projection`, `transaction_dependency`, budget/project/goal and journal relationship summaries rebuild synchronously from authoritative revisions and immutable facts. History reads Refund account/amount and exposes original/refunded/remaining plus the three dates. | `P16-E003` |

Full, partial, repeated partial, cross-month, other-account, independent and explicit-excess paths all use exact integer minor units. Receiving-account FX evidence is frozen by the existing amount-evidence model; current rates cannot reinterpret a historical refund.

## Original transaction dependency policy

An original transaction with active linked refunds cannot be silently changed or removed. A trash request must provide one complete policy per linked refund:

- reverse the dependent refund, appending its REVERSE Journal/effects/allocation before reversing the original; or
- append a refund revision that converts it to independent, reversing the old allocation and applying the replacement before reversing the original.

All child commands and the original command are one canonical `BatchFinancialCommand`, one expected-revision check set and one Room transaction. Missing, duplicate or incompatible resolutions fail before commit. Ordinary edit remains blocked while a refund dependency is unresolved. This realizes the frozen cascade/independent/prevent choice without mutating old facts. Installment identity remains attached to the original and is surfaced in REC-015 for the later installment-specific allocation extension.

## UI contract and accessibility

| Screen | Frozen states and components | P16 implementation |
|---|---|---|
| REC-015 | linked, independent, partiallyRefunded, exceedsRemaining, saving; original, amount, receiving account/card, inherited fields, budget/project/goal policies, advanced override, fixed Save FAB | Displays original/refunded/remaining/current amounts together; inherited category/merchant/project/settlement/goal remain adjustable where applicable; cash/accrual/budget dimensions are separate; excess is in a danger container and requires a second confirmation. Invalid or failed saves preserve the in-memory draft. |
| REC-016 | content, empty, searching; search, refundable rows, filter chips | Bounded encrypted query by category/merchant with a true partial-only predicate; rows expose refundable status without notes/private values; independent mode remains available from the empty state. |

The exact 8 required states render across 320/360/480dp, 100/130/200% font, Simplified Chinese/Japanese/English and light/dark themes. The high-risk confirmation and all three time dimensions are scroll-reachable at 320dp/200%. Two 360×720 exact-pixel SHA-256 baselines cover linked-light and excess-dark.

Those baselines were produced only from the implemented Compose tree, UI main contract, token JSON, screen YAML, traceability matrix and localized resources. No excluded PNG/HTML visual draft was opened, parsed, sampled, measured, compared or used as a baseline.

## Retained later-stage boundaries

- P19 owns credit-card statement and installment-specific refund allocation/recalculation UI; P16 retains the typed installment relation only.
- P24 owns advanced cross-feature batch operation orchestration.
- P25/P26 own the remaining analysis/report presentation built from the facts P16 already emits.
- P31 still owns maintenance-only physical purge; P16 only adds dependency-safe reversible transitions.
- P34/P36 retain whole-product accessibility, performance and release acceptance.

No P17 or later feature is promoted by P16.
