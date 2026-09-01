# P26 custom analytics mapping

P26 is `VERIFIED`. This mapping covers only custom reports, dashboards, deterministic anomaly/forecast analysis and the typed report-export handoff. Physical export files remain P29.

## Frozen contract coverage

| Surface | Implementation | Verification |
|---|---|---|
| ANA-006 dashboards | Encrypted dashboard list plus saved-report edit/copy actions | `P26-E003`, `P26-E004` |
| ANA-007 editor | Named revision, palette, stable ordering, full/half metric width and explicit invalid/empty-canvas state | `P26-E003`, `P26-E004` |
| ANA-008 builder | Closed measure/dimension/filter/granularity/comparison/sort/visualization choices, preview, save and opaque drilldown | `P26-E001`, `P26-E002`, `P26-E004` |
| ANA-009 visualization | Compatibility reason, accessible alternative and category-count pie fallback | `P26-E002`, `P26-E004` |
| ANA-010 export | IMAGE/PDF/CSV/XLSX typed data payload, period scope and sensitive-field notice; no file-success claim | `P26-E001`, `P26-E003`, `P26-E004` |
| ANA-013 anomaly | Five closed configurable rules, immutable revisions, result disclosures and invalid/empty states | `P26-E002`—`P26-E004` |
| ANA-014 forecast | Closed forecast keys, current daily average, optional recurrence, historical same-month, assumptions and insufficient-data state | `P26-E002`—`P26-E004` |

The YAML oracle contains exactly 7 P26 screens and 16 required states. Device coverage distributes 320/360/480dp, 100/130/200% font scale, zh-CN/ja-JP/en-US and light/dark boundaries across the complete P25+P26 36-state analysis matrix.

## Domain and deterministic methods

`ReportSpec` remains the only report language. Measures, dimensions, filter nodes/operators/values, sorting, comparison and visualization are closed Kotlin types; the existing SQL compiler accepts only allowlisted projections, columns, joins and bound parameters. No UI or user input can provide SQL, a mathematical formula or executable script.

`DefaultDeterministicAnalyticsEngine` and `ReportDerivationPolicy` use injected date/Clock inputs, explicit `AnalyticsAlgorithmVersion`, `BigInteger`, `BigDecimal` and checked integer operations. They implement:

- historical mean/standard deviation, recent growth, large-single-transaction and merchant/category frequency anomaly rules;
- current daily-average, recurrence-inclusive and historical same-month forecasts;
- versioned moving average, trend and forecast derived series.

Every finding/result carries its version, input window, threshold or assumptions, baseline and explanation code. Empty historical samples are rejected as insufficient data; missing years are never inserted as zero. Same ordered or reordered inputs, date and version have deterministic golden/property equality.

## Schema v2 and lifecycle

Schema v2 expands the encrypted primary SQLCipher database with normalized tables for report current/revision/measure/dimension/sort/filter-tree/filter-values, dashboard current/revision/items and anomaly-rule current/revision. It contains no universal JSON/payload or plaintext side store. Revision rows are append-only; current rows use explicit `expectedRowVersion` conflict detection. Configuration changes do not pretend to be financial commits and do not advance `book.localRevision`. Security startup inspection reads `LedgerMigrations.CURRENT_VERSION`, so UI and headless sessions cannot silently remain pinned to an obsolete logical schema.

Room registers only the adjacent non-destructive v1→v2 migration and exports both schema JSON versions. API 36 SQLCipher tests prove v2 create/reopen, v1 ledger-row retention through migration, contract-hash switch, integrity checks and normalized custom report/copy/dashboard/anomaly/forecast round trips.

## Boundaries

- Routes contain only `StableId?`, `StableId`, or the closed `ForecastKey.routeKey`; report specs, names, amounts, notes, locations, attachments and result rows remain outside routes/SavedState.
- Feature code receives only `AnalyticsApplicationPort`; SQLCipher/Room access remains in `:analytics:data`/`:core:database`.
- P26 performs no financial write. It neither exposes DAO/Entity nor bypasses `FinancialMutationCoordinator`.
- ANA-010 prepares typed export data only. IMAGE/PDF/CSV/XLSX file creation, SAF delivery and complete backup behavior remain P29.
- No AI SDK, AI/OCR action, formula language or script engine exists in the P26 production surface.
- P26 goldens were captured only from production Compose, frozen textual contracts, YAML/CSV and token JSON. None of the excluded PNG/HTML visual drafts was opened, parsed, sampled, measured or compared.
