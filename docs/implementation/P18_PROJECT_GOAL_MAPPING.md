# P18 Project and Goal-Fund Mapping

P18 is `VERIFIED`. This mapping covers project budgets/reports and account-bound goal reservations, their immutable facts and synchronous projections, the typed application/data boundary, and PRJ-001—PRJ-006 plus GOL-001—GOL-005. It does not promote recurrence authoring, settlement management, analytics aggregation, widgets, operations, or any later phase.

## Frozen model to implementation

| Frozen surface | Production implementation | Evidence |
|---|---|---|
| `Project` current/audit lifecycle | Typed `SaveProjectRequest` and `ChangeProjectStatusRequest`; row-version and `localRevision` checks; immutable entity revision/change audit; explicit ACTIVE ↔ ARCHIVED policy | `P18-E001`—`P18-E003` |
| Project identity and one-goal relation | One nullable `goalId` column/request value enforces at most one goal per project; the reverse query exposes every bound project on one goal | `P18-E001`, `P18-E003`, `P18-E004` |
| Project budget usage | `ProjectEffect` stores checked base-minor amounts and the transaction-time `includedInMonthlyBudgetSnapshot`; `ProjectUsageProjection` exposes gross use, refund restoration and remaining independently | `P18-E002`, `P18-E003` |
| Project accounting scope | Expense usage takes the frozen SELF_SHARE amount; transfers create no ProjectEffect; loan principal is excluded while actual interest, fee and penalty effects consume the project budget | `P18-E002`, `P18-E003` |
| Project reports | Encrypted queries expose recent transactions, keyset-paged complete transactions, expense/income/net cash flow and settlement activity status; Vico wrappers always have an accessible data table | `P18-E001`, `P18-E003`—`P18-E005` |
| `Goal` current/audit lifecycle | Typed create/edit with immutable account/currency after creation, target/due/suggested values, row version and explicit completion strategy | `P18-E001`, `P18-E003`, `P18-E004` |
| Manual goal operations | `RecordGoalMovementCommand` carries command ID, expected goal row version, positive exact-minor amount, operation date/time and canonical hash; planning creates only immutable `GoalMovement`/`GoalEffect` facts through `FinancialMutationCoordinator` | `P18-E001`—`P18-E003` |
| Transaction goal effects | Ordinary target spending and refund restoration use typed single `goalId` inputs and emit SPEND/RESTORE facts; the goal projection rebuilds from manual and transaction effects | `P18-E002`, `P18-E003` |
| Account availability | `GoalBalancePolicy` and the SQLCipher adapter derive `actual balance - all goal reserves` with checked integers; negative values remain valid and surface a non-blocking warning | `P18-E002`—`P18-E004` |
| Completion | RELEASE appends an explicit release before completion, KEEP retains the reservation, and CONTINUE remains active; every path keeps the real account balance unchanged | `P18-E003`, `P18-E004` |

## Write and query boundary

Project and goal configuration are non-financial entity mutations with explicit revision/audit records. Manual goal funding, transaction spending and refund restoration are financial mutations and terminate exclusively at `FinancialMutationCoordinator`; no feature, ViewModel or adapter directly inserts a goal/project financial fact. The Room repository rechecks the goal row version and ACTIVE state inside the same SQLCipher commit that writes the receipt, commit, immutable facts, projection and book revision.

PRJ-004 uses keyset Paging on `(occurredAt, StableId)` with a bounded page size and no deep OFFSET. The project overview queries only three recent rows. Project/goal routes carry only optional/required `StableId` values and the closed `ALLOCATE|RELEASE|ADJUST` enum; names, descriptions, amounts, accounts and dates remain in memory/application requests. The root derives create/edit and entity status from the encrypted snapshot and fails closed for an unknown ID, so a stale relationship route cannot silently create a replacement object.

## Frozen behavior interpretations

- The frozen `GoalMovement` shape stores a positive amount and a closed kind. `ADJUST` is therefore an explicit positive correction; a downward correction uses the explicit `RELEASE` kind. No signed value is hidden inside a positive-money field.
- RELEASE completion is a resumable two-commit workflow because the frozen data model has separate financial command receipts and entity revisions. The release commit occurs first. If the following status mutation fails, retry observes zero remaining reserve and only completes the status, so it cannot duplicate a release or corrupt the balance.
- A project monthly-budget setting change affects only future transactions. Existing ProjectEffect and BudgetEffect rows are never rewritten or silently recomputed.

## UI and accessibility coverage

The 31 required states across the 11 P18 screens render in the exact YAML state oracle. The matrix covers 320/360/480dp, 100/130/200% font scale, zh-CN/ja-JP/en-US and light/dark themes. Project list/status tabs, fixed detail tabs, compact filters, date fields, non-blocking underfunding warning, account availability formula, movement history, relationship navigation and completion choices use governed components and stable tags. Both project cash-flow and goal-trend charts expose readable tables.

Two 360×720 Compose full-pixel SHA-256 baselines cover light project cash flow and dark underfunded goal detail. They are generated only from production Compose, frozen tokens and the textual/YAML contracts; excluded visual artifacts were not used as an input or oracle.

## Scope boundary

P18 verifies REQ-046, REQ-047, REQ-051 and REQ-052, plus PRJ-001—006 and GOL-001—005. Settlement authoring remains P22, recurrence-generated allocation remains P23, cross-feature analytics remains P25/P34, widgets remain P33, and final performance/release acceptance remains P35/P36.
