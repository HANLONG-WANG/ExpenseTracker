# P33 Widget, More, Settings and Operation Mapping

P33 is `VERIFIED`. This mapping is derived only from the frozen textual UI contract, token JSON, screen YAML and traceability CSV. No excluded visual PNG/HTML draft was opened or used.

## Widget boundary

The nine frozen widget types are `QUICK_ENTRY`, `MONTH_CONSUMPTION`, `MONTH_BUDGET`, `TODAY_AVAILABLE`, `ACCOUNT`, `CORE_NET_ASSETS`, `CREDIT_CARD`, `GOAL` and `FINANCIAL_OVERVIEW`.

| Contract | Production realization | Verification |
|---|---|---|
| WGT-001 type selection | One Android AppWidget configuration Activity exposes exactly the nine closed types in a governed Compose grid | `P33-E001`, `P33-E004` |
| WGT-002 data/no data | Account/card/goal and category/template selections use bounded eligible rows; no eligible row has an explicit return path | `P33-E003`, `P33-E004` |
| WGT-003 privacy | `revealAmounts` defaults false in every independently persisted widget configuration; an explicit opt-in affects only that widget | `P33-E002`, `P33-E004` |
| Launcher rendering | Glance reads only the four widget snapshot tables from the primary SQLCipher database and renders configured/locked/stale/no-data states | `P33-E001`—`P33-E004` |
| Quick entry | The deep link carries closed kind/direction plus one stable target ID; the app revalidates fresh references and opens REC-003 without saving | `P33-E001`, `P33-E002` |

The read model has no transaction, note, merchant, location, coordinate, attachment or Vault field. App-lock state is deliberately absent: launcher content follows explicit per-widget privacy consent, while unavailable key material produces a truthful locked state. Ready-session/foreground date changes rebuild only the four derived snapshots in one transaction; Glance never performs that maintenance.

## Snapshot/schema ownership

Primary Schema v3 extends the existing typed projections rather than introducing a parallel generic table:

- `widget_book_snapshot`: committed revisions, local date/month, current/prior consumption, budget available/used, today available and current/prior core net assets.
- `widget_account_snapshot`: stable account ID, display label, balance/available, currency and local revision.
- `widget_credit_snapshot`: stable account ID, display label, debt/limit/statement remainder/due date, currency and local revision.
- `widget_goal_snapshot`: stable goal ID, display label, balance/target, currency and local revision.

Every normal financial commit rebuilds these through the existing projection engine. P33's cross-date refresh is derived-only and transactional; it is not a financial write and creates no alternate coordinator.

## More, transfer and settings reachability

| Surface | Closed behavior |
|---|---|
| G-006 | One no-drawer More hub groups planning, liabilities, settlement, automation, data, reference and settings. Durable failures/active operations plus automation/settlement updates drive text or badge states. |
| TRF-001 | Import, export, backup, restore and Operation Center share one transfer hub with active-operation and notification-permission status. |
| G-007 | Encrypted durable operations load newest-first and render active/paused/failed/completed/empty states without parameter/path disclosure. Cancel persists a request so Worker cleanup can finish. |
| G-008 | Five packaged help topics are allowlisted; an unknown key renders notFound and no free URL is accepted. |
| SETG-001—005/008/012 | Appearance, three-language region settings, currencies, calendar, trash and about/licenses/source are reachable through the same governed design system. |
| SYS-002 | First-use rationale and denied/settings-recovery states gate long-operation notifications. |

All non-bottom-navigation product capabilities are reachable from G-006, either directly or through the settings/transfer hubs. No navigation drawer or isolated route is introduced.

## Background and notification safety

Import, export and backup Workers reject any InputData key set other than the singleton `operationId`. Full descriptors and checkpoints remain encrypted in SQLCipher. The centralized low-priority ongoing notification contains bounded progress/status and a closed `ledger://screen/G-007` deep link. Cancel enters durable `CANCEL_REQUESTED` and then typed cleanup/rollback; it does not kill the Worker before cleanup.

## Evidence closure

- Static contract and eight weakening mutations: `P33-E001`.
- Nine-type/default-hidden/stale/no-data/key-lock JVM policy: `P33-E002`.
- Real SQLCipher v1→v3 and cross-date idempotent projection refresh: `P33-E003`.
- WGT-001—003 and three languages on API 28/API 36: `P33-E004`.
- All G/SETG/TRF/SYS states and three languages on API 36: `P33-E005`.
- Encrypted operation listing, safe cancel and notification/Worker boundary: `P33-E006`.
- Aggregate architecture/source/format/Detekt/JVM/Lint gate: `P33-E007`.
- Frozen baseline, all script mutations, artifacts and repository hygiene: `P33-E008`.

P34 and later stages are not claimed.
