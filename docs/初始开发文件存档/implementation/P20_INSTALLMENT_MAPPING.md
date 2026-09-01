# P20 Credit Installment Mapping

P20 is `VERIFIED`. This mapping covers purchase-linked installment plans, immutable terms and schedule versions, exact principal/cost calculation, read-only early-settlement simulation, explicit settlement application, refund allocation/recalculation, the synchronized progress projection, and REC-027 plus INS-001—INS-006. It does not promote loan schedules, recurrence, analytics, widgets, physical purge or later release work.

## Frozen model to implementation

| Frozen surface | Production implementation | Evidence |
|---|---|---|
| `InstallmentPlan` | Typed current aggregate links exactly one already-recorded credit purchase, original/current principal, status and current revision; a second plan for the same purchase is rejected | `P20-E001`—`P20-E003` |
| Terms revision | `InstallmentPlanRevision` closes fee type, term count, prepayment and refund policies, rates/fees and rounding; `expectedRevisionId` prevents silent replacement | `P20-E001`—`P20-E003` |
| Schedule revision/items | `InstallmentAccountingPolicy` generates ordered statement items with checked integer minor units; the last term absorbs every division tail and the remaining-principal chain must reach zero | `P20-E002`, `P20-E003` |
| Fee models | NONE, fixed-per-term, first-term-fixed, remaining-principal rate and effective annual rate are mutually exclusive typed variants; actual annual cost is reported separately from principal | `P20-E002` |
| Purchase accounting | The original credit purchase retains its one complete expense/liability Journal. Schedule items are future statement assignments, never new transactions or consumption effects | `P20-E002`—`P20-E004` |
| Posted versus unposted | `InstallmentProgress` and its projection separate posted liability from unposted commitment while conserving current principal | `P20-E002`—`P20-E004` |
| Early settlement simulation | Current schedule, date and prepayment policy produce principal, future cost, fee, payment and saved-cost comparison without a command, receipt or revision | `P20-E002`, `P20-E003`, `P20-E004` |
| Settlement application | Explicit high-risk confirmation submits one typed command; the same coordinator transaction appends the settled plan/schedule version and one real asset/liability repayment, with any fee as non-consumption expense | `P20-E003`, `P20-E004` |
| Refund allocation | A committed refund revision is validated against the plan's purchase relation; explicit principal/fee allocation creates a new plan and schedule version while preserving the old plan | `P20-E002`, `P20-E003`, `P20-E004` |
| Idempotency and conflict | Canonical full-input command hash plus `CommandReceipt` makes an exact retry stable and rejects a reused command with changed payload; stale plan revision fails | `P20-E001`, `P20-E003` |

## Lifecycle and Schema v1 mapping

| Lifecycle | P20 representation |
|---|---|
| Current | `installment_plan.current_revision_id`, current status and the typed `InstallmentPlanView` |
| Revision | `installment_plan_revision` and `installment_schedule_revision`; old terms and schedules are retained |
| Fact | Original purchase Journal/Posting/EconomicEffect, explicit settlement transaction facts and `installment_refund_allocation`; a schedule item is not a financial fact |
| Projection | `installment_progress_projection` rebuilt at the committed `book.localRevision` from current/revision/fact state |
| Cache | No network or mutable external estimate is authoritative; P20 creates no installment cache |
| Operation | Preview and simulation are read-only; save, settlement and refund application have typed command identities and receipts |

The frozen Schema v1 already contains `installment_plan`, `installment_plan_revision`, `installment_schedule_revision`, `installment_schedule_item`, `installment_refund_allocation` and `installment_progress_projection`, so P20 adds no migration, universal JSON payload, side database or plaintext path. Schedule item identity uses the frozen revision-local `(schedule_revision_id, installment_no)` row and an internal deterministic write ID; it is never exposed as a route or a business transaction.

## Accounting, write and query boundary

`InstallmentAccountingPolicy` is pure Kotlin and uses `Long` checked arithmetic plus `BigDecimal` DECIMAL128/HALF_EVEN for rate multiplication. It rejects overflow, invalid remaining chains, mismatched item counts and illegal fee/policy combinations. No authoritative installment path uses `Float` or `Double`.

All installment plan saves and every actual settlement/refund mutation terminate at `FinancialMutationCoordinator`. `SecureRoomInstallmentApplicationPort` may read the encrypted snapshot and construct a typed plan, but only `RoomInstallmentPlanWriter` is called inside the normalized financial repository transaction. Revision, facts, current pointer, receipt, progress projection and `book.localRevision` commit or roll back together. Feature/app code has no DAO, Entity, SQL, Journal or Posting capability.

The purchase remains one complete expense/liability event. The schedule affects only future statement assignment and progress. Applying settlement generates a real repayment and a separate non-consumption settlement fee where required; it never rewrites or re-counts the purchase. Refund recalculation accepts only a real committed refund allocation linked to the plan's purchase and preserves both old schedule and old terms revisions.

## Route, UI and accessibility coverage

Routes carry only optional/required `StableId` values for the purchase transaction or installment plan. Principal, fee, rate, dates, account/card/name/note/attachment/location data and full objects remain outside route/SavedState.

The exact 19 required states across REC-027 and INS-001—006 render against the YAML oracle. The device matrix covers 320/360/480dp, 100/130/200% font scale, zh-CN/ja-JP/en-US and light/dark boundaries. It includes create/edit/invalid/preview/saving, empty/active/completed/refund-adjusted, full accessible schedule table, simulation comparison, unreachable invalid apply and explicit refund decision. Settlement application uses a second high-risk confirmation with scope, consequence and unaffected facts.

Two 360×720 Compose full-pixel SHA-256 baselines cover active installment detail in light theme and calculated settlement in dark theme. They are generated only from production Compose, frozen tokens and textual/YAML/CSV contracts; no excluded visual artifact is an input or oracle.

## Scope boundary

P20 verifies REQ-040 and the seven listed screen contracts. Loan plans remain P21, recurrence remains P23, cross-feature analytics remains P25/P34, widgets remain P33, and final scale/release acceptance remains P35/P36.
