# P13 Category-first ordinary recording mapping

P13 is `VERIFIED`. The implementation covers 12 screens and all 42 required states frozen as `requiredStates` for REC-001—REC-012. Visual inputs were limited to the textual UI contract, token JSON, screen YAML and traceability CSV; the excluded PNG/HTML visual drafts were never opened, parsed, measured or used as baselines.

| Frozen surface | Production mapping | Verification |
|---|---|---|
| Category-first entry | Fixed expense/income/other tabs, independent category trees, grouped full `CategoryGrid`, direct first/second-level selection, search, empty states and five-template strip | `P13-E002`, `P13-E004` |
| Ordinary editor | One vertical REC-003 form in the exact category → amount/currency → account/card → merchant → time/zone → project → settlement → location → note → attachment order; advanced immutable statistical/budget summary follows the required fields | `P13-E002`, `P13-E004`, `P13-E005` |
| Exact amount and defaults | P03 decimal expression evaluator and legal-tender metadata; integer-minor result only; manual → template → category → compatible recent → cash/first-active defaults; account changes clear incompatible cards | `P13-E002` |
| Application write | `OrdinaryTransactionWriteRequest` carries typed IDs/values and `expectedRevisionId`; `SecureRoomOrdinaryTransactionEntryPort` reconstructs the encrypted snapshot and calls the sole `FinancialMutationCoordinator`; no ViewModel/Composable creates a Journal or writes a DAO | `P13-E003`, `P13-E006` |
| Atomic revision lifecycle | Create/edit, canonical command hash, duplicate receipt, revision conflict, immutable facts/effects/current pointer/projections/location side effect and book revision execute inside the P08 transaction | `P13-E003` |
| Location and attachment | P10 foreground prefetch shares one remaining three-second save budget and never supplements later; SAF content streams into the encrypted object store, supports cancellation and cleans uncommitted objects | `P13-E003`, inherited `P10-E003`—`P10-E006` |
| Privacy/navigation | Routes carry only closed enums and opaque stable IDs. Amount, note, entity names, card data, attachment data and coordinates remain in memory/encrypted storage and are excluded from SavedState, logs and semantic values | `P13-E001`, `P13-E006`, `P13-E008` |
| Exit/return behavior | Unsaved edits require discard/continue confirmation; submission is single-flight; failures preserve the in-memory form; create/template/duplicate return to the originating category tab, edit to detail, candidate/batch to their typed origin | `P13-E002`, `P13-E004` |
| UI matrix | REC-001—012 render through governed components at 320/360/480dp, 100/130/200% fonts, zh-CN/ja-JP/en-US, light/dark and reduced-motion boundaries; four exact API-36 goldens are captured from the implemented Compose/token tree | `P13-E004`, `P13-E005` |

P13 does not claim implementation of the six specialized transaction destinations (P14+), goal management/underfunding behavior (P18), settlement management (P22), template authoring (P23), import mapping (P28), journal UI or final P34/P36 acceptance. Their requirement rows remain `IN_PROGRESS` where appropriate; the six REC-012 entry actions are present without pretending their later workflows are complete.
