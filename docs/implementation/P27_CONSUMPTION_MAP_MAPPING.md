# P27 Consumption Map Mapping

P27 implements REQ-056 and the complete ANA-011/ANA-012 contract from the frozen textual specifications. It does not use any excluded PNG/HTML visual draft. The map is a derived query surface: it cannot write financial data, rewrite historical FX evidence, or become a second location capture path.

## Contract and accounting mapping

| Frozen requirement | Production realization | Verification |
|---|---|---|
| Default current month and four measures | `ConsumptionMapQuery` defaults to the active report month and the closed `CONSUMPTION`, `ALL_EXPENSES`, `CASH_FLOW`, and `ALL_LOCATED_TRANSACTIONS` modes | `ConsumptionMapContractTest`; `AnalyticsSqlCipherDeviceTest` |
| Amount/count and merchant/place | Closed weight and aggregation enums select database-side aggregate expressions; no raw point list is materialized first | `P27-E002`, `P27-E003` |
| Complete filters | Account, category, merchant, place, project, transaction kind and exact checked base-minor amount bounds are typed. Active filter option queries are bounded to 200 rows per dimension. Same-dimension values accumulate as OR (maximum 64), dimensions are AND, every selected value is an independently removable chip, and reset returns to the current-month/default-exclusion query | `validate_p27_consumption_map.py`; `ConsumptionMapFilterRemovalTest`; API 36 option/query and two-account chip assertions |
| Historical FX | Consumption/all-expense modes read immutable `economic_effect.base_amount_minor`; cash flow reads frozen base-currency cash/bank `posting`; all-located uses frozen effect/posting evidence. Current rates are never consulted | `consumptionMapUsesRTreeFrozenBaseAmountsDefaultExclusionsAndOpaqueDrilldown` |
| Default exclusions | Transfer, credit repayment, loan disbursement and loan repayment are excluded by the typed default and can be included only by an explicit control | domain and SQLCipher device tests |
| Geographic query | `location_rtree` supplies viewport candidates, exact E7 predicates remove RTree floating bounds false positives, and existing radius queries retain RTree candidates plus Kotlin Haversine distance | `P27-E002`, `P27-E003`; `INDEX-FAMILY-02` |
| Target scale | SQL groups before returning data and emits at most 512 render points; summary totals still cover the full viewport. A 10,000-row encrypted device fixture proves bounded nodes and viewport updates | `tenThousandLocatedTransactionsRemainDatabaseAggregatedViewportBoundedAndNodeBounded` |

## Map and accessibility boundary

`LedgerMap` is owned by `:core:geo` and hosted by the app root. The feature supplies only typed presentation state and a composable slot, preserving the feature-to-SDK boundary. MapLibre receives incremental GeoJSON source updates without recentering. Cluster, heat and single-point layers use the governed map design contract; heat uses the token `sequential teal` scale, not semantic red/green. MapLibre attribution remains enabled. User location is an optional double-ring graphic on a separate source and is visually distinct from transaction points.

The map never creates one Compose node per geographic record. ANA-011 always retains a bounded, clickable location list over the same query result. `mapUnavailable` uses that same list and summary, so a network/style/renderer failure does not remove filter, amount/count, or drilldown access. ANA-012 provides location/cluster/transaction summary, accessible category data table, bounded transaction preview and an opaque keyset drilldown.

Exact coordinates can be visually inspected in the detail sheet when required, but `clearAndSetSemantics` replaces them with a privacy-safe “location recorded” description. Coordinates, labels, amounts and result objects never enter navigation, SavedState or logs. ANA-012 carries only `placeOrClusterId:StableId`; the in-memory selection registry expires on local-revision drift.

## Exact screen-state coverage

| Screen | Route | Required states |
|---|---|---|
| ANA-011 | `analysis/map` | `loading`, `clusters`, `heatmap`, `singlePoints`, `noLocationData`, `mapUnavailable` |
| ANA-012 | `analysis/map/location/{placeOrClusterId}` | `place`, `cluster`, `singleTransaction` |

The 9 required states are checked exactly, not by a subset assertion. Device UI tests cover 320/360/480dp, 100/130/200% font, zh-CN/ja-JP/en-US, light/dark boundaries and independent removal from a two-value account OR condition. The two pixel digests are generated exclusively from production Compose, governed tokens and textual/YAML contracts: `4bfbfc87834852c480b51e4fd411ce3f26b4d04217c1a40ae50f459bf181304d` and `bcf9ce902212d1aa707f3e5a5b5f8e901c63e9255deddf0fe4f6740792e3cc61`.

## Stage boundary

P27 is `VERIFIED`. It adds no online place search, reverse geocoding, background location, financial DAO access, financial write, import/export runtime or later-stage screen. P28 remains the next unstarted stage.
