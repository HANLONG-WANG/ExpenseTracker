# P25 Analytics Engine, Fixed Reports and Integrity Mapping

P25 is `VERIFIED`. This mapping freezes the production boundary for the typed analytics engine, the 20 fixed reports, ANA-001—ANA-005/ANA-015, and the integrity workflow. It does not claim the P26 custom dashboard/report-builder screens, P27 map reports, later import/export transports, or final release acceptance.

## Authoritative query boundary

`ReportSpec` is a closed, bounded AST: measures, dimensions, filters, granularity, comparison and sorting are enums/sealed values with fixed node/list limits. `ReportSqlCompiler` selects only the twelve analytics rollups or the closed immutable fact/projection sources, emits only allowlisted columns/JOINs/aggregates, binds every user value, limits result rows, and never accepts SQL or formula text. Drilldown uses an opaque in-memory `DrilldownQueryId`, a stable keyset cursor and at most 100 rows per page; it never carries the query, amount, name, note, card, attachment or location in a route or SavedState.

Every query plan records `asOfLocalRevision` and, where valuation is relevant, `asOfValuationRevision`. Stale analytics rows are not presented as current. Original-currency subledger measures carry an explicit `CurrencyCode`; base-value measures stay in the book base currency, so unrelated currencies are never silently summed or formatted as one another. FX revaluation subtracts historical base carrying value from current base valuation rather than mixing native and base minor units.

## Fixed report catalog

| Group | Fixed report keys | Governing semantics |
|---|---|---|
| Income and expense | `income-expense-net`, `cash-flow`, `consumption-category-structure`, `category-trend`, `merchant-ranking-trend`, `consumption-map`, `refunds-contra-expense`, `multi-dimensional` | Income, expense, consumption, non-consumption expense and contra-expense remain separate; refunds reduce expense; savings rate is `(income - all expense) / income` with checked integer accumulation and exact decimal division |
| Assets and liabilities | `account-balance-net-financial-assets`, `fx-revaluation`, `credit-debt-statement-limit`, `installment-balance-fees`, `loan-principal-interest-progress-forecast`, `multi-currency-fx-cost` | Transfers, credit repayments and loan principal are not expense; current valuation is versioned separately; installments do not duplicate the purchase as new consumption; principal and interest retain distinct semantics |
| Planning | `budget-execution`, `project-budget-cash-flow`, `goal-funds`, `recurrence-subscriptions` | Budget/project effects use their dedicated immutable effect families; goal reservations do not alter actual account balance; recurrence candidates never enter facts or aggregates |
| Relationships | `settlement-activity` | Settlement position uses the typed subledger; settlement payments do not create duplicate income or expense |
| Data quality | `data-integrity` | The report entry delegates to the nine-check encrypted integrity workflow and does not expose ordinary users to journal-side terminology |

`FixedReportCatalog` proves the catalog has exactly 20 entries, covers every frozen report and every fixed group once or more, and has unique stable keys. Pie is accepted only up to six categories; a larger result deterministically switches to a bar representation and explains the fallback. All chart cards provide a textual summary and the exact accessible data table used by TalkBack and export.

## Projection and encrypted data mapping

`AnalyticsProjectionEngine` synchronously rebuilds these narrow projections inside the same Room/SQLCipher transaction that `FinancialMutationCoordinator` already owns:

- daily and monthly totals;
- daily and monthly category;
- daily and monthly account;
- daily and monthly merchant;
- daily and monthly project;
- daily and monthly place.

The rebuild derives only from immutable Posting/EconomicEffect/current projection facts, stamps one `as_of_local_revision`, and produces a canonical SHA-256 digest. A savepoint audit rebuilds all twelve tables, hashes them, and rolls back the audit copy; a mismatch becomes `staleRebuildRequired`. P25 introduces no second write entrance: analytics queries are read-only, the synchronous projection call remains below `FinancialMutationCoordinator`, and the explicit repair action is a maintenance-mode projection rebuild rather than a feature/Worker financial DAO write.

`SecureRoomAnalyticsApplicationPort` opens only the main SQLCipher database through the per-book key provider, clears the passphrase buffer, and exposes typed overview/fixed-report/drilldown/export/integrity results. The integrity report checks database integrity, foreign keys, balanced Journals, Posting currency evidence, revision chains, projection versions/hash, FTS, R*Tree and complete fact rebuild. Technical details remain collapsed and desensitized.

## UI and navigation mapping

| Screen | Production state coverage | Implementation |
|---|---|---|
| ANA-001 | `content`, `noData`, `calculating`, `error` | Analysis overview, metric cards, governed chart/table and report entry grid |
| ANA-002 | `content` | Complete grouped 20-report catalog |
| ANA-003 | `loading`, `content`, `empty`, `queryError`, `staleRebuildRequired` | Header, metrics, Vico wrapper, summary, accessible data table, drilldown and export action |
| ANA-004 | `editing`, `invalid` | Closed measure/dimension/filter/granularity/comparison editor; no SQL/formula input |
| ANA-005 | `content`, `empty`, `expiredQuery` | Bounded keyset transaction result from opaque `queryId` |
| ANA-015 | `notRun`, `running`, `passed`, `warnings`, `failed` | Nine check groups, collapsed technical detail and explicit projection repair |

The root SessionGate remains authoritative before any analysis destination. ANA-003 carries a closed `reportKey` plus optional StableId; ANA-005 carries only a StableId query identifier. Vico is accessed only through `LedgerVicoLineRenderer`, `LedgerVicoColumnRenderer`, `LedgerVicoStackedRenderer` and `LedgerVicoPieRenderer`. Production render tests cover 320/360/480dp, 100/130/200% font, simplified Chinese/Japanese/English, light/dark themes and TalkBack-readable summaries/data tables.

## Verification map

- `P25-E001`: exact YAML/AST/catalog/compiler/route/static and mutation proof.
- `P25-E002`: domain policies and SQL compiler unit proof for all 20 reports.
- `P25-E003`: API 36 SQLCipher execution, semantics, revision gating, integrity and identical rebuild hash.
- `P25-E004`: all 20 required UI states, responsive/font/locale/theme and accessibility matrix.
- `P25-E005`: production Compose pixel digests derived only from textual contracts, token JSON and screen YAML.
- `P25-E006`: formatting, Detekt, Lint, architecture and app-root regressions.
- `P25-E007`: frozen-source, full script, dependency and repository-hygiene replay.

No excluded visual PNG/HTML draft was opened, parsed, sampled, measured, compared or used to establish any pixel baseline.
