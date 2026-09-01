# P17 Monthly Budget and Rollover Mapping

P17 is `VERIFIED`. This mapping covers the natural-month budget aggregate, immutable revisions and adjustments, reconstructible rollover projection, daily-available calculation, the typed application boundary, and BUD-001—BUD-008. It does not promote goals, projects, recurrence authoring, analytics, operations beyond the existing recalculation link, or any later phase.

## Frozen model to implementation

| Frozen surface | Production implementation | Evidence |
|---|---|---|
| `BudgetTemplate` / revision / category limits | Closed `BudgetTemplateMutation`, immutable `BudgetTemplateRevision`, explicit current pointer and typed `SaveBudgetTemplateRequest` | `P17-E001`—`P17-E003` |
| `BudgetMonth` / revision | Independent `YearMonth` aggregate, immutable revision history, `expectedRevisionId`, explicit base total and typed category limit list | `P17-E001`—`P17-E003` |
| Total, first-level and second-level constraints | `BudgetHierarchyPolicy` and `BudgetConstraintPolicy`; roots must identify themselves, children must identify their frozen root/parent; checked sums enforce `INV-018`/`INV-019` | `P17-E002`, `P17-E004` |
| `BudgetAdjustment` | Immutable signed total/category facts for clear, increase, decrease and paired transfer; no Journal, Posting, EconomicEffect or account projection is created | `P17-E002`, `P17-E003` |
| `BudgetRollover` | `BudgetRolloverEngine` and `RoomProjectionEngine.rebuildBudget` derive each natural-month link from the prior remaining amount, the current base, effects and adjustments; positive and negative values are uncapped and the chain never expires | `P17-E002`, `P17-E003` |
| Budget usage | One leaf `BudgetEffect` is counted once in total; first-level usage includes its direct and second-level leaf usage; child usage remains direct, preventing parent/child double counting | `P17-E002`, `P17-E003` |
| Daily available | `DailyAvailableBudgetPolicy` computes `(remainingDisposable - futureRecurrenceReservation) / remainingDays` with exact signed integers and exposes every operand | `P17-E002`, `P17-E003`, `P17-E004` |
| Current/stale projection lifecycle | Every synchronous commit stamps `as_of_local_revision`; a mismatch returns `FAILED` with empty composition/daily values and links to the operation center instead of presenting an old cache as current | `P17-E001`, `P17-E003`, `P17-E004` |

All budget mutations terminate at `FinancialMutationCoordinator`. `SecureRoomBudgetApplicationPort` may read current/immutable budget rows to build a plan but contains no financial `INSERT`, `UPDATE` or `DELETE`. The coordinator owns command receipt lookup, expected-revision validation, atomic current/revision/fact writes, projection rebuild and `book.localRevision` advance.

## Constraint and composition semantics

- The base constraint meter uses only the month/template base total and base category limits. Rollover and adjustments are deliberately excluded from the limit test.
- Unclassified consumption has `category_id = null`, appears in total usage and therefore still consumes the total budget.
- A child effect is stored once. The projection includes it in child, its first-level parent and the total through separate aggregation, never by duplicating the fact.
- Base, rollover, adjustment, used and remaining/exceeded are separate query/UI fields. No aggregate is packed into JSON or inferred from a formatted string.
- A historical budget or transaction change causes a synchronous deterministic rebuild beginning with the first relevant natural month and extending through the last affected month. A ten-year chain is the same loop as a two-month chain; the 122-month property proves that no bounded expiry or cap exists.
- Clearing rollover appends a budget adjustment equal to the inverse of the selected current rollover. It cannot fabricate a transaction or touch account balances.

## Idempotency and conflict handling

The port reconstructs a command from the request's immutable expected revision rather than the mutable current pointer. This makes retry payloads stable: an exact duplicate `commandId` returns the original receipt, while a new command pointing to an obsolete revision reaches the coordinator and fails the expected-revision precondition. A rejected hierarchy or stale revision leaves the book, current pointers, facts and projections unchanged.

## UI and route boundary

| Screen | Implemented contract states |
|---|---|
| BUD-001 | `configured`, `notConfigured`, `recalculating`, `historical`, `future` |
| BUD-002 | `editing`, `constraintError`, `saving`, `historyRecalculationWarning` |
| BUD-003 | `editing`, `constraintError` |
| BUD-004 | `content`, `empty` |
| BUD-005 | `editing`, `invalid`, `saving` |
| BUD-006 | `content`, `singleRevision` |
| BUD-007 | `content`, `empty` |
| BUD-008 | `create`, `edit`, `constraintError` |

The 8 screens cover all 23 required states. Routes carry only `YYYYMM`, a closed adjustment enum and optional stable category/template IDs. Amounts, category names, template names and the editor draft remain in memory and never enter route/SavedState. Fixed design-system semantic test tags contain no runtime state or business values.

The UI explicitly exposes base/rollover/adjustment/used/remaining, formula operands, hierarchy difference meters, historical-rebuild warning, future-month editing, immutable adjustment history and no-account-impact text. Compact and 200% font layouts use lazy scrolling and wrapping adjustment actions.

## Evidence and retained boundaries

- `P17-E001`: exact YAML/source/privacy/ledger validator and six mutation rejections.
- `P17-E002`: domain/state policy suites, including 1,000 hierarchy samples and 122 months of positive/negative rollover.
- `P17-E003`: API 36 SQLCipher/Keystore integration, command idempotency, history rewrite, template versions, signed adjustments, integrity/FK and canonical rebuild hash.
- `P17-E004`: all 23 states under 320/360/480dp, 100/130/200% font, zh-CN/ja-JP/en-US and light/dark.
- `P17-E005`: two exact-pixel Compose SHA-256 goldens.
- `P17-E006`: architecture/source policy/format/Detekt/Lint aggregate.
- `P17-E007`: frozen-source, script-mutation and repository-hygiene replay.

P23 owns recurrence authoring and occurrence reservation production. P25/P34 own complete analytics and cross-feature reporting. P28+ owns durable long-operation presentation. P17 consumes the existing reservation projection contract and links failed recalculation to the shared operation center without claiming those later stages.
