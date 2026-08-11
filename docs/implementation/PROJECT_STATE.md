# Project State

Last updated: 2026-08-11 (Asia/Tokyo)
Current stage: P33 — desktop widgets, More hub and remaining system/settings flows
Stage status: VERIFIED (`P33-E001`—`P33-E008`); P00—P32 remain VERIFIED and P34 is the next unstarted stage
P01 starting Git commit: `cb4d66e581c1c5e55c02c64089a5461ac9bae249`

## Recovery protocol after context compression

1. Read this file and the other six ledgers in `docs/implementation/`.
2. Run `python3 scripts/validate_spec_baseline.py` and `python3 scripts/validate_p01_baseline.py` before changing implementation status.
3. Re-read the current phase's cited frozen chapters and query the complete JSON/YAML/CSV inputs that it touches.
4. Never open, parse, hash, screenshot, sample, measure or otherwise inspect the four visual drafts named below.
5. Preserve existing work; inspect `git status --short --branch` before edits.
6. Update evidence first, then promote a row. `VERIFIED` requires a reproducible test/command, environment and result in `TEST_EVIDENCE.md`.

Unified row states:

- `NOT_STARTED`: no compliant production implementation exists.
- `IN_PROGRESS`: implementation has begun but is incomplete or not yet internally complete.
- `IMPLEMENTED`: compliant implementation exists, but required verification evidence is incomplete.
- `VERIFIED`: required repeatable evidence is recorded and passing.
- `BLOCKED`: completion is impossible until the documented external condition changes; code-solvable work is not a blocker.

## Specification baseline

The implementation precedence is:

1. Product requirements, system architecture, domain/accounting/security invariants.
2. Frozen technical stack.
3. UI main contract semantics, interactions and component governance.
4. Token JSON concrete values.
5. Screen YAML coverage.
6. UI traceability matrix acceptance mapping.

`MANIFEST.sha256` is used only for integrity verification. The following visual drafts are excluded implementation inputs: `UI视觉样稿_浅色.png`, `UI视觉样稿_深色.png`, `UI视觉样稿_完整总览.png`, `UI视觉样稿_v1.html`.

### Frozen file fingerprints

| File | SHA-256 at P00 |
|---|---|
| `docs/规格冻结_v1.0/需求.md` | `539723ce5abca31747e1b3d2f75ab705d3acca6b3ecd69ab7552b9ec0ac906b7` |
| `docs/规格冻结_v1.0/技术栈.md` | `9bc8aa0a214795909f6c0d9cbfebffe73d58b9e0688e56a48dc85bbd805f0dc0` |
| `docs/规格冻结_v1.0/系统架构.md` | `c8033e8696b52909ea61d4459866b914bee5c9369ccc376beb44b8c1f7e2c171` |
| `docs/规格冻结_v1.0/领域模型与数据库逻辑模型设计.md` | `e519ea2bd99d2afce305bad720f0c874bb297e7a500e96b404448f08d4d916de` |
| `docs/UI设计稿与实现契约_v1.0/README_交付说明.md` | `65e90b4329d2f79af3b6e9e6ff3f6d8baf613c3238570de9cfacbcab0d358e97` |
| `docs/UI设计稿与实现契约_v1.0/Android记账软件_UI设计系统与实现契约_v1.0.md` | `050cbbee9f6236eadd7d3194ea539ed4641b3c2f999957222247e778dc3daaf7` |
| `docs/UI设计稿与实现契约_v1.0/android_ledger_ui_tokens_v1.json` | `d7be41816bfe1d53b0b9b521de69b60dd193b0a1a040f2c749e3099ef5fc0b1f` |
| `docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml` | `70c5077ee7cc91e996dbeabbcfcaf3b8052b1eb76501a774c4acc249cd3dc3c7` |
| `docs/UI设计稿与实现契约_v1.0/UI需求追踪矩阵_v1.csv` | `4c587e22497e693594b61995efb3527711b6552b5ecf862efdbe4a89827d1049` |
| `docs/UI设计稿与实现契约_v1.0/MANIFEST.sha256` | `f1fa76e2ca3ec3da839496471d9f890ee7e830e9238e2e1edfa873a812f924bc` |

## Repository inventory and preserved baseline

| Area | Observed baseline | Classification |
|---|---|---|
| Git | Branch `master`, tracking `origin/master`; no tracked modifications at start | Correct baseline |
| User work | Untracked `docs/Android记账软件_完整开发计划_Codex执行版.md` (SHA-256 `692eab49f7a11224dde6cbdecbe009e496554237eab7ba3d607e5e38094cf97a`) | Preserved; planning input, not a frozen specification |
| Frozen specifications | Four frozen product/technical/architecture/domain Markdown files and the UI contract package are tracked | Correct and integrity-baselined |
| Gradle root | No `settings.gradle*`, `build.gradle*`, `gradlew`, wrapper, version catalog or build logic | Missing; P01 scope |
| Modules | No `:app`, `:benchmark`, `:build-logic`, core, finance, analytics, transfer, feature or widget modules | Missing; P01 scope |
| Production source/resources | No Kotlin/Java, Manifest, Compose, XML, assets, localization, schema or migration files | Missing; P01 and later phases |
| Tests | No JVM, Android, UI, migration, screenshot, accessibility, performance or device tests | Missing; P02 and later phases |
| Scripts/CI | No pre-existing validation scripts or `.github` CI | Missing; P00 adds only the spec baseline validator; CI is P02 scope |
| Generated/build output | No project build products, schema exports, reports, AAB/APK, SBOM or Baseline Profile | Missing as expected |
| Partial implementation | None | None to integrate |
| Conflicting/deprecated implementation | None | None to remove; excluded visual drafts remain tracked review material but are not implementation inputs |

Baseline conclusion: this is a documentation-only repository, not a partial Android application. P00 must not create placeholder production code or claim any feature implementation.

### P01 result

| Area | P01 result | Classification |
|---|---|---|
| Toolchain | Temurin JDK 17.0.20; Gradle Wrapper 9.5.1; AGP 9.3.1; Kotlin 2.4.10; Android compile/target 36 and min 28 | VERIFIED |
| Build governance | AGP built-in Kotlin, Kotlin Compose compiler plugin, KSP 2.3.10, version catalog and seven convention/architecture plugins in the included `:build-logic` build | VERIFIED |
| Module topology | 35 prescribed leaf modules plus five zero-dependency Gradle grouping projects; `:build-logic` is an included build | VERIFIED |
| Dependency direction | Exact allowlisted project graph enforces UI → Application → Domain ← Infrastructure; eight domain/common modules are pure Kotlin/JVM; feature modules have no feature/data/DAO/Room-entity edge | VERIFIED |
| Reproducibility | Wrapper distribution SHA-256 pinned; 37 lockfiles cover root, build logic and all leaf modules; strict dependency verification contains 1,382 components and 2,848 SHA-256 entries after the P28 streaming-import graph was sealed | VERIFIED |
| Production source | Secure application manifest only; no business screen, DAO, persistence, placeholder component or fake functionality was added | Correct for P01 |
| Tests and scripts | P00 and P01 structural validators, Gradle architecture/version checks, Android Lint and all configured JVM test tasks | VERIFIED for P01 scope |
| CI, advanced quality and release | Detekt/format/Kover/SBOM/license/CI/Baseline Profile/release AAB are not introduced early | P02/P36 scope |

P01 preserved all prior tracked and untracked work. The provisional build identity `app.ledger.expensetracker` is not a claim about the final externally supplied Play identity; see `DL-010`.

### P02 result

| Area | P02 result | Classification |
|---|---|---|
| JVM and Android test stack | JUnit 5, Kotest Property/assertions, MockK, coroutines-test, Turbine, AndroidX Test/JUnit4, Compose UI Test, Espresso, MockWebServer and Room MigrationTestHelper are centrally configured; JVM tests and all four API 28/API 36 device harnesses execute | VERIFIED (`P02-E002`, `P02-E008`) |
| Static quality | Android Lint, pinned stable detekt CLI, Spotless + ktlint and Kover are wired into repeatable root tasks | VERIFIED (`P02-E003`, `P02-E005`, `P02-E006`) |
| Architecture/privacy policy | Project/external dependency boundaries plus alias/type/scope-aware source rules cover governed UI, route/SavedState wrappers, telemetry/log aliases, nondeterminism and Coordinator-owned DAO writes; named production-shaped fixtures prove rejection | VERIFIED (`P02-E003`, `P02-E004`) |
| Traceability | Complete token JSON, screen YAML and requirement/screen CSV ledgers are cross-checked with full canonical hashes: 434 scalar tokens, 90 REQs, 215 unique IDs/routes, 646 exact per-screen required states and 192 explicitly mapped screens; equal-count drift is rejected | VERIFIED (`P02-E001`) |
| Supply chain | 37 lockfiles, strict root and standalone build-logic verification metadata, aggregate CycloneDX 1.6 JSON/XML SBOM, CSV/HTML OSS inventory and Kover XML/HTML reports generate in normal strict mode | VERIFIED (`P02-E006`) |
| CI and performance base | GitHub Actions is configured for aggregate/static/supply-chain proofs plus API 28/API 36 Managed Device matrix; local `:benchmark` executes its rule contract and local `:core:database` executes MigrationTestHelper on API 36 | VERIFIED as local P02 infrastructure (`P02-E007`, `P02-E008`); remote run remains `UNVERIFIED`; measurements/profile remain P35/P36 |
| Screenshot provenance | No screenshot or golden is fabricated for the page-free shell. Later baselines may be captured only from implemented Compose UI derived from textual contracts/tokens; excluded visual drafts remain unread | Correct for P02; see `DL-013` |

P02 is complete for its local acceptance scope. `/dev/kvm` is accessible, Emulator 37.1.11 reports usable KVM 12, and the API 28 app, API 36 app, API 36 benchmark and API 36 MigrationTestHelper Managed Device tasks all passed with zero failed/skipped tests. Remote Actions remains explicitly unverified. See `P02-E007`—`P02-E008`.

### P03 result

| Area | P03 result | Classification |
|---|---|---|
| Common value foundation | Positive `InternalId`, defensively immutable 16-byte `StableId`, distinct `CommandId`/`RevisionId`, injected UUID source, typed errors and immutable `DomainResult` live in `:core:common` | VERIFIED (`P03-E002`) |
| Checked arithmetic | Every Long add/subtract/multiply/negate/absolute/accumulate entry uses exact checks; BigInteger accumulation and exact Long conversion report typed overflow | VERIFIED; permanent `INV-034` (`P03-E002`, `P03-E006`) |
| Money and FX | `Money(Long minor, CurrencyCode)`, current country legal-tender metadata, currency-specific scales, explicit rounding/MathContext, immutable `FxEvidence` and exact conversion are pure Kotlin | VERIFIED as P03 foundation (`P03-E003`) |
| Amount expression | Bounded tokenizer/Pratt parser/BigDecimal evaluator supports only `+ - * / × ÷ ( )`, whitespace/full-width/local decimal normalization, exact source positions, positive result and currency-minor rounding | VERIFIED (`P03-E004`) |
| Time and periods | Injected Clock, self-consistent `EffectiveTime`, default DST-gap rejection, explicit shift provenance/overlap policies, storage keys, natural budget months and account-zone statement cycles use only `java.time` | VERIFIED (`P03-E005`) |
| Formatting boundary | Currency/date-time formatters return preformatted UI models, preserve locale/zone evidence and prevent hidden-value leakage; no Composable performs authoritative work because P04 UI is not started | VERIFIED as P03 interface (`P03-E003`, `P03-E005`) |
| Static and regression gates | `p03Check`, `p03Artifacts`, pure-Kotlin validator and named Float/Double + sum/fold/reduce/`+=`/manual-loop rejection fixtures extend all P02 gates | VERIFIED (`P03-E006`—`P03-E009`) |

P03 is complete with 36 core behavioral tests plus 12 build-logic policy tests (all zero failed/error/skipped), 1,000-case addition/absolute/expression properties, generated calendar boundaries, and fresh P03-inclusive Kover reports. No Android/Room/network dependency exists in the three core modules; visual drafts are excluded by fail-closed textual input policy.

### P04 result

| Area | P04 result | Classification |
|---|---|---|
| Token/theme contract | All 434 JSON scalar paths generate deterministically; typed `LedgerTheme.colors/typography/spacing/shapes/motion/dimensions`, 16 category pairs, semantic colors, chart colors and dynamic-shell boundaries are mapped and contrast-tested | VERIFIED (`P04-E001`, `P04-E002`) |
| Governed components | 44 core component APIs plus closed variants/models, unified icon registry, load/error/empty states, financial formatting consumers, high-risk confirmation, accessibility semantics and stable test tags live only in `:core:designsystem` | VERIFIED as P04 foundation (`P04-E003`, `P04-E005`, `P04-E007`, `P04-E008`) |
| Navigation | Navigation 3 keys mirror all 215 unique YAML routes and 646 required states; only typed stable IDs/closed enum-period-mask-int arguments cross the public boundary; five top-level stacks retain independent history and pass SessionGate/current-stack back tests | VERIFIED; `REQ-085` (`P04-E004`, `P04-E005`) |
| Chart/map/widget boundary | Vico adapter interfaces, accessible data table and deterministic pie fallback are present; Map and Glance expose restricted token/design subsets and mandatory map-list fallback without pretending the later external SDK integrations exist | VERIFIED for P04 contract; SDK capability remains P26/P33 (`P04-E002`, `P04-E003`, `P04-E007`, `P04-E008`) |
| Visual/accessibility matrix | Token-only 128×104 golden, 320/360/480dp, 100/130/200% font scale, zh/ja/en, light/dark, reduced motion, 48dp touch, hidden-value semantic leak and map fallback tests pass on API 28 and API 36 | VERIFIED (`P04-E007`, `P04-E008`) |
| Static governance | Feature code is rejected for unwrapped Material components, color/general-dp literals, MaterialTheme, external icon families, swipe delete, duplicate governed components and runtime/sensitive test tags; production source passes and named fixtures fail | VERIFIED (`P04-E005`) |
| Provenance | Generated Kotlin and the golden use only explicit token JSON/screen YAML inputs; the golden records token traversal, alpha composition, digest and renderer tolerance in `quality/screenshot/P04_GOLDEN_PROVENANCE.md` | VERIFIED (`P04-E001`, `P04-E007`, `P04-E008`) |

P04 creates no feature page, database write, SDK-backed map/widget, or fake persistence. Every row in `SCREEN_COVERAGE.csv` therefore remains `NOT_STARTED`; the independently verified cross-cutting route/state shell for all 215 screens is recorded here and in the generated contract evidence instead of misrepresenting screen UI completion.

### P05 result

| Area | P05 result | Classification |
|---|---|---|
| Lifecycle and aggregates | Six closed lifecycle markers separate Current/Revision/Fact/Projection/Cache/Operation records; Book/Commit/receipt, accounts/cards/classification, eleven transaction payloads, immutable revisions/amount evidence, Journal/Posting/effects and every frozen subledger aggregate are represented as pure Kotlin types | VERIFIED (`P05-E001`, `P05-E002`) |
| Compile-time closure | Ordinary expense/income commands require typed category and payer values; transaction context exposes only one project and goal; formal account money can be constructed only by the currency-checking factory; transaction payloads are sealed to the eleven frozen kinds | VERIFIED (`P05-E001`, `P05-E002`, `P05-E003`) |
| Mutation boundary | Commands carry expected revisions; `DefaultFinancialMutationCoordinator` performs idempotency lookup, snapshot, planning, domain validation and one atomic commit under the write gate; no UI, Worker or importer receives a financial DAO/Entity | VERIFIED as P05 API boundary (`P05-E002`, `P05-E003`); Room transaction implementation remains P07/P08 |
| Query, analytics and transfer ports | Typed current/account/budget/project/installment/loan/widget projections and filters, closed 20-report analytics AST, durable operation state, staging/shadow/backup/restore/merge contracts and opaque operation launch token are present | VERIFIED for P05 contracts (`P05-E001`, `P05-E002`); adapters and algorithms remain their frozen later phases |
| Governance and evidence | Exact §1—§35 mapping, P05 contract validator/mutation suite, generic-domain-payload rejection, pure-module architecture gate, 29 domain/application tests and 3,000 generated invariant cases pass | VERIFIED (`P05-E001`—`P05-E006`) |

P05 introduces no Room Entity/DAO, SQLCipher behavior, Android dependency, feature page, fake repository or pretend persistence. The complete accounting-rule planners remain P06 and the physical schema/projections remain P07/P08; therefore target requirement rows truthfully remain `IN_PROGRESS` and all 215 screen rows remain `NOT_STARTED`.

### P06 result

| Area | P06 result | Classification |
|---|---|---|
| Deterministic planning | One pure planner closes all 11 frozen transaction payloads, consumes only explicit IDs/time/reference/FX/current-fact inputs and generates the same immutable plan for the same input | VERIFIED (`P06-E001`, `P06-E002`) |
| Accounting facts and effects | Ordinary expense/income, transfer, opening balance, adjustment and FX exchange generate balanced Posting/Journal facts; refund, credit, loan and settlement rules emit all typed Effect families without a universal transaction object | VERIFIED (`P06-E001`, `P06-E002`) |
| Revision lifecycle | Create/restore append APPLY; edit appends exact old-version REVERSE plus new APPLY; trash appends exact REVERSE; journal-less external settlement follows the same lifecycle; archived references remain reversible | VERIFIED (`P06-E002`) |
| Exact money and FX | USER_INPUT/ACCOUNT/BASE evidence, frozen rate/provider/timestamps, FX clearing/rounding/cost/gain and all authoritative accumulation use checked integer/decimal paths; commit roots cover facts and evidence | VERIFIED (`P06-E001`—`P06-E003`) |
| Conflicts and idempotency | Canonical command hashes are validated before receipt lookup; duplicate command IDs return only an identical first receipt; stale expected revisions and incompatible/incomplete dependency policies fail before commit | VERIFIED (`P06-E002`) |
| Permanent invariants | `P06_ACCOUNTING_INVARIANT_MAPPING.md` maps all 35 permanent invariants; the 25 accounting-core rows have named automated P06 evidence while database/feature portions remain in their owning phases | VERIFIED for P06 scope (`P06-E001`—`P06-E006`) |

P06 contains no Room/SQLCipher adapter, projection persistence, feature page or platform claim. The nine target requirement rows remain truthfully `IN_PROGRESS`, the physical schema/projection families remain `NOT_STARTED`, and all 215 screen rows remain `NOT_STARTED`.

### P07 result

| Area | P07 result | Classification |
|---|---|---|
| Encrypted database topology | Room 2.8.4 owns one SQLCipher 4.17.0 primary database and an independently versioned one-operation SQLCipher import staging database; no framework-SQLite or plaintext side database path exists | VERIFIED (`P07-E001`, `P07-E003`) |
| Complete Schema v1 | The exact 94 frozen §25 tables plus explicit `rule_set_version`, all §26 projections, §27 FTS5/R*Tree and §28 operation/import/backup/restore records produce 140 declared primary tables; all seven staging tables are separate | VERIFIED (`P07-E001`, `P07-E002`, `P07-E003`) |
| Constraints and immutability | Foreign keys, row `CHECK`s, unique/reversal constraints, 39 named indexes, four audit views, cross-row constraints and 63 Revision/Fact append-only guards are installed; no `@Upsert` or universal JSON payload exists | VERIFIED for Schema v1 (`P07-E001`—`P07-E004`); P08 owns repository transactions |
| SQLite/SQLCipher operation | WAL, foreign keys, incremental auto-vacuum, memory-only temporary storage, secure delete, controlled checkpoint/optimize and cipher memory protection are fail-closed connection settings | VERIFIED on API 36 (`P07-E003`) |
| Capabilities and leakage | The official x86_64 AAR reports SQLCipher 4.17.0 and executes FTS5 trigram, R*Tree, JSON and window queries; correct-key reopen, wrong-key rejection, integrity/FK checks and database/WAL sensitive-sentinel scans pass | VERIFIED on API 36 (`P07-E003`) |
| Migration governance | Both Room v1 JSON identities and complete canonical raw-DDL JSON catalogs are checked in; v1 has no predecessor, future versions require adjacent explicit Expand → Backfill → Switch → Contract migrations, and destructive fallback is rejected | VERIFIED (`P07-E001`, `P07-E002`, `P07-E004`) |

P07 adds no repository implementation, DAO-to-domain mapper, projection rebuild algorithm, feature page, background runtime, import execution or controlled purge workflow. Those remain P08 and their owning later stages; all 215 screen rows remain `NOT_STARTED`.

### P08 result

| Area | P08 result | Classification |
|---|---|---|
| One write entry | `FinancialCommandHandler`, the submit use case, `DefaultFinancialMutationCoordinator` and a mutex-backed `DefaultLedgerWriteGate` form the only application write path; source policies deny privileged planning/commit ports and financial SQL to features, Workers and importers | VERIFIED (`P08-E001`, `P08-E002`, `P08-E004`) |
| Atomic persistence | `RoomFinancialCommitRepository` re-checks receipt/book/head/rule/expected revision and appends commit, Revision, frozen evidence, facts, Effects, pointers, projections, book revision and receipt in one Room-owned SQLCipher transaction | VERIFIED on API 36 (`P08-E003`) |
| Mapper breadth | The normalized writer covers all 11 typed transaction details, Journal/Posting, seven Effect families, refund/credit/loan allocations, goal/budget facts and entity changes without JSON or `@Upsert` | VERIFIED (`P08-E001`, `P08-E003`) |
| Synchronous projections | Current transactions, balance/daily, refund, budget, project, goal, credit, installment, loan, settlement, FTS/R*Tree and four widget snapshot families rebuild from authoritative facts at the target local revision; deferred valuation/future/analytics revisions are not falsely advanced | VERIFIED for the P08 synchronous set (`P08-E001`, `P08-E003`) |
| Audit/recovery | Canonical projection hashes, savepoint-based dry rebuild audit, atomic maintenance rebuild, integrity/subtype/version/count checks and a lightweight startup disposition are implemented | VERIFIED (`P08-E003`) |
| Query base | Bound type-safe filters, stable keyset paging, FTS candidate selection plus exact typed predicates, and R*Tree candidate selection plus Kotlin Haversine distance are implemented with closed limits and no `OFFSET` | VERIFIED (`P08-E001`—`P08-E003`) |

P08 creates no feature page, Worker/import execution, later analytics/valuation projection, widget runtime or physical purge workflow. `P08_REPOSITORY_PROJECTION_MAPPING.md` records the exact implementation boundary. All 215 screen rows remain `NOT_STARTED`, and P09+ is not promoted.

### P09 result

| Area | P09 result | Classification |
|---|---|---|
| Separate key hierarchies | DeviceLedgerKEK wraps the SQLCipher DEK, attachment root and secure-settings key; VaultAuthenticationKEK wraps only the Vault DEK; Argon2id derives an independent recovery-password KEK with versioned calibrated parameters | VERIFIED (`P09-E001`, `P09-E003`, `P09-E004`) |
| Cryptographic primitives | Tink AES-256-GCM and 1 MiB-segment AES-GCM-HKDF Streaming AEAD use canonical book/purpose/card/field/blob/schema/version associated data; secrets/passwords use defensive bounded wrappers and zeroization | VERIFIED (`P09-E001`, `P09-E002`) |
| Book runtime | `BookSessionManager` owns production SQLCipher open/inspect/close across Locked/Opening/Maintenance/RecoveryRequired/Ready; opaque capability-limited headless leases keep background access separate from UI unlock | VERIFIED on JVM and API 36 (`P09-E001`, `P09-E003`) |
| Vault authentication | Every provision/reveal/copy/edit/export request binds one exact `Cipher` to `BiometricPrompt.CryptoObject`; security-code copy is absent, plaintext expires after 30 seconds and clears on background/app lock | VERIFIED with real device credential on API 36 (`P09-E001`, `P09-E004`) |
| App lock and screen privacy | App lock defaults off, requires authentication to enable, uses monotonic immediate/1/5/15 minute or bounded custom timeouts, obscures recents and supports optional global `FLAG_SECURE` without weakening mandatory vault protection | VERIFIED as P09 runtime policy (`P09-E001`, `P09-E002`); UI/lifecycle wiring remains P32 |
| Fail-closed governance | Missing/deleted keys enter recovery instead of regeneration; feature sources cannot obtain security capabilities; raw secret Strings, security wrappers in SavedState, ordinary logging and telemetry maps remain statically rejected | VERIFIED (`P09-E002`, `P09-E003`, `P09-E005`) |

P09 implements no SessionGate/settings/vault/backup/restore/clear-data screen, backup transport, restore/merge flow, telemetry queue or production Worker wiring. `P09_SECURITY_RUNTIME_MAPPING.md` records the exact boundary. All 215 screen rows remain `NOT_STARTED`, and P10+ is not promoted.

### P10 result

| Area | P10 result | Classification |
|---|---|---|
| Encrypted object store | Reopenable SAF sources stream through SHA-256 and Tink Streaming AEAD into app-private staging, atomically move under random opaque names, deduplicate by hash+size, and commit attachment/blob metadata in Room/SQLCipher transactions | VERIFIED on API 36 (`P10-E001`—`P10-E003`) |
| Failure and retention lifecycle | Cancellation, I/O/database failure and interrupted-process recovery remove staging/orphan objects without leaving a database reference to a missing object; history and backup references gate delayed GC | VERIFIED (`P10-E002`, `P10-E003`) |
| Secure presentation and sharing | Coil 3 decrypts originals/thumbnails without disk/network cache; app lock clears memory; preview is zoomable; the non-exported Provider decrypts to a pipe only after explicit confirmation and consumes a 60-second one-time URI | VERIFIED on API 36 (`P10-E003`, `P10-E005`) |
| Foreground location | Fused Location Provider falls back to `LocationManager`, requires foreground permission only, freezes fixed-point evidence within the three-second save budget, and cancels rather than performing a later background write | VERIFIED on API 36 (`P10-E002`, `P10-E004`) |
| Map foundation | `LedgerMap` owns MapLibre lifecycle/style/attribution and token-derived cluster, heatmap and single-point layers; every unavailable renderer/style path keeps the accessible data-table alternative | VERIFIED on API 36 (`P10-E004`) |
| Contract UI | ATT-001—003 and SYS-001 are complete; REC-009/010 render all required infrastructure states at 320dp/200% font but remain `IN_PROGRESS` until P13 wires the complete record form/application flow; ANA-011/012 remain P27 | VERIFIED for P10 scope (`P10-E004`, `P10-E005`) |
| Static privacy boundary | Feature code cannot import `:core:files`/`:core:geo` or Coil/MapLibre/Fused SDKs; production background location, shared attachment storage, sensitive route/state values and ordinary logging remain rejected | VERIFIED (`P10-E001`, `P10-E006`) |

P10 does not claim the P13 complete record form, P27 analytics map screens, background location, online place search/reverse geocoding, or later import/backup Workers. `P10_FILES_GEO_MAPPING.md` records the exact implementation and later-stage boundary.

### P11 result

| Area | P11 result | Classification |
|---|---|---|
| One root and SessionGate | The manifest exposes one Hilt `MainActivity` and one Compose root; Locked, Opening, Maintenance, RecoveryRequired and Ready are mutually exclusive root states, and pending deep links are consumed only after Ready | VERIFIED on API 36 (`P11-E003`, `P11-E007`) |
| Five-stack navigation | Navigation 3 owns five independent typed back stacks; cold start is `REC-001`/`EXPENSE`, current-tab reselection pops only that stack, and safe non-sensitive snapshots preserve each stack and scroll position | VERIFIED (`P11-E002`, `P11-E003`) |
| Global shell behavior | One governed `LedgerScaffold` owns the five-entry navigation bar, shared More/operation/help destinations, global Snackbar controller and process-loss/operation banner slot; non-Ready sessions cannot navigate or submit a financial command | VERIFIED (`P11-E002`—`P11-E005`) |
| First launch | The exact ten ordered onboarding steps validate language, currency, time zone and privacy; optional recovery, first account and first category may be skipped; encrypted bootstrap creates no example transaction, Journal, Posting, effect or balance | VERIFIED on JVM and API 36 (`P11-E002`, `P11-E003`) |
| Typed persistence and privacy | Proto DataStore stores only non-sensitive locale/consent/lock policy, wrapped recovery verifier, book stable ID and allowlisted navigation state; recovery plaintext is bounded, cleared on exit and absent from route, SavedState, logs and semantics | VERIFIED (`P11-E001`, `P11-E002`, `P11-E004`) |
| UI contract matrix | All 65 required states across G-001—008 and ONB-001—010 render with privacy semantics under compact/regular/wide widths, 100/130/200% font, three locales, light/dark and the dynamic-color boundary | VERIFIED on API 28 and API 36 (`P11-E004`, `P11-E005`) |
| Contract-rendered goldens | Four 360×720 root/onboarding PNG baselines originate only from governed Compose/token output and compare pixel-for-pixel; their dimensions and SHA-256 values are machine-frozen | VERIFIED on API 36 (`P11-E006`) |

P11 does not claim later feature pages, settings completion, durable operation execution, backup/restore transport, widget runtime, full feature-wide accessibility/performance acceptance or later process-death form behavior. `P11_APP_SHELL_MAPPING.md` records the exact implementation boundary.

### P12 result (verified)

| Area | P12 result | Classification |
|---|---|---|
| Accounts and balances | Four account types, appearance, archive/empty-delete rules, first-Posting currency lock, coordinator-owned opening balance, checkpoint-only audit, exact net-position naming and missing-valuation behavior are implemented | PASS for implemented scope (`P12-E002`—`P12-E004`) |
| Physical cards | Separate account/card current records, bank/credit compatibility, zero/many cards, archive and replacement history are implemented | PASS for implemented scope (`P12-E002`, `P12-E003`) |
| Categories | Independent income/expense trees, maximum depth, immutable second-level parent, governed appearance, accessible ordering, search, defaults, statistical snapshot, archive, tombstone and atomic historical reassignment are implemented | PASS (`P12-E002`—`P12-E004`, `P12-E008`) |
| Merchants and places | Merchant alias search/merge, linked places, offline fixed-point place editing/merge/split and governed MapLibre/list fallback are implemented without reverse geocoding | PASS (`P12-E003`, `P12-E004`, `P12-E008`) |
| Account/read UI | Account home/detail/transactions, Vico balance trend and accessible table, cards, goal projection and recent transactions render from encrypted projection-backed data | PASS on API 36 (`P12-E004`) |
| UI contract matrix | Closed enums cover all 23 P12 screens and 67 exact YAML states; compact/wide, 100/130/200% font, three locales, light/dark and dynamic boundary execute | PASS on API 36; API 28 remains recorded separately (`P12-E004`, `P12-E006`) |
| Historical category reassignment | Coordinator-owned `BatchFinancialCommand` appends typed EDIT revisions and REVERSE/APPLY facts using frozen historical amount/FX evidence | PASS: one canonical `BATCH_MUTATION`, one receipt and one Room transaction (`P12-E008`, `DL-058`) |
| Place split | Immutable location clones plus current-transaction revision fan-out execute through the same batch coordinator path | PASS: source records stay immutable and the entire reference/financial change is atomic (`P12-E003`, `P12-E008`, `DL-058`) |

P12 is `VERIFIED`. `P12_REFERENCE_DATA_MAPPING.md` records the exact implemented boundary. No direct feature/Worker/importer financial DAO path, mutable-history workaround, sequential partial financial commit or second application write entry was introduced.

### P13 result (verified)

| Area | P13 result | Classification |
|---|---|---|
| Category-first recording | Fixed expense/income/other tabs, independent full category grids, search/empty states, first/second-level direct selection and editable template entry | VERIFIED (`P13-E002`, `P13-E004`) |
| Complete editor | Exact field order, system-keyboard expression, governed date/time picker, compatible account/card defaults, settlement, location, protected note semantics, encrypted attachments and advanced snapshots | VERIFIED (`P13-E002`, `P13-E004`, `P13-E005`) |
| Atomic application write | One typed application request reaches `FinancialMutationCoordinator`; idempotency, expected revision, immutable edit facts, location side effect and synchronous projections share one SQLCipher transaction | VERIFIED (`P13-E003`, `P13-E006`) |
| Failure/privacy behavior | Invalid Save locates errors, submitting is single-flight, failures retain input, conflict is explicit, unsaved exit is confirmed, sensitive draft data stays out of routes/SavedState/logs/semantic values | VERIFIED (`P13-E002`, `P13-E004`, `P13-E006`) |
| UI evidence | 12 REC screens/42 states, widths/font scales, three languages, light/dark/reduced-motion and accessibility semantics pass; three P13-owned exact goldens remain active and REC-011's complete regression moved to P22 | VERIFIED (`P13-E004`, `P13-E005`, `P22-E005`) |

P13 remains `VERIFIED`. `P13_ORDINARY_RECORDING_MAPPING.md` records its handoff boundary; P14 closes only the transfer/opening/adjustment/FX subset while P18 goal behavior, P22 settlement management, P23 template authoring, P28 import flow and later acceptance remain unpromoted.

### P14 result (verified)

| Area | P14 result | Classification |
|---|---|---|
| Specialized financial writes | Internal transfer, balance adjustment, FX exchange and opening balance use closed typed requests and the P06 planner through the sole `FinancialMutationCoordinator`; duplicate command remains idempotent and every SQLCipher Journal balances | VERIFIED (`P14-E002`, `P14-E004`) |
| Multicurrency evidence | Same-currency single amount, cross-currency dual authoritative amounts, original/account/base minor units, latest/cache/manual/historical/implied sources, quote times and exact spread/rounding evidence are frozen per revision | VERIFIED (`P14-E002`—`P14-E004`) |
| Statistics and checkpoints | Opening/adjustment create no income, expense, consumption or budget effect; checkpoint Fact remains immutable and its reverse association is derived from the adjustment revision detail | VERIFIED (`P14-E004`, `DL-064`) |
| Current valuation and offline policy | Privacy-limited pair/date network adapter, encrypted timestamped cache, stale state and manual fallback are implemented; current refresh advances only `valuationRevision`, while historical facts and `localRevision` remain unchanged | VERIFIED (`P14-E003`, `P14-E004`) |
| Currency settings | Legal-tender search, visible/hidden state, persistent order and mandatory base/account currencies implement SETG-004 without storing financial or sensitive values | VERIFIED (`P14-E002`, `P14-E005`) |
| UI and screenshots | REC-013/020/021/022 and SETG-004 cover all 15 YAML states under the P14 width/font/locale/theme matrix; four 360×720 token/Compose goldens compare exactly | VERIFIED (`P14-E005`, `P14-E006`) |

P14 remains `VERIFIED`. `P14_MULTICURRENCY_MAPPING.md` records the exact application/data/UI boundary; P15 does not reinterpret its frozen amount or FX evidence.

### P15 result (verified)

| Area | P15 result | Classification |
|---|---|---|
| Large journal/query path | Bounded keyset Paging, date headers, complete filter algebra, parameterized FTS5 plus exact R*Tree/Haversine filtering, account-only running balances and no deep `OFFSET` | VERIFIED (`P15-E002`, `P15-E003`) |
| Selection and batch editing | All-matching selection stores only query fingerprint and exceptions; exactly eight allowed fields execute as one coordinator-owned immutable batch, while amount/direction/refund/share remain forbidden | VERIFIED (`P15-E002`, `P15-E004`) |
| Detail and immutable history | Complete detail/FX/relationship/source reads, revision timeline, comparison and old-version restore append RESTORE plus REVERSE/APPLY facts without overwriting history | VERIFIED (`P15-E004`, `P15-E005`) |
| Trash and purge boundary | Move/restore are typed coordinator commands; retention, financial net, dependencies, operations and backup references produce explicit eligibility reasons; P31 retains physical maintenance purge | VERIFIED for P15 (`P15-E004`, `DL-069`) |
| Saved filters and privacy | Save/copy/default/reorder/delete persist under per-book AEAD in no-backup storage; values never enter routes, SavedState, logs or semantic values | VERIFIED (`P15-E004`, `P15-E007`) |
| UI and screenshots | JRN-001—JRN-012 cover all 42 YAML states at the width/font/locale/theme matrix; two token/Compose goldens compare exactly and no swipe-delete exists | VERIFIED (`P15-E005`, `P15-E006`) |

P15 is `VERIFIED`. `P15_JOURNAL_MAPPING.md` records the exact query/application/UI and retained P31 purge boundary. No refund, credit, loan, budget, goal, analytics, import, physical purge or later feature stage is promoted.

### P16 result (verified)

| Area | P16 result | Classification |
|---|---|---|
| Refund facts and accounting | Linked/independent, full/partial/repeated/cross-month/other-account/excess refunds append balanced Journals, contra-expense and typed restore effects plus immutable allocation/reversal facts | VERIFIED (`P16-E002`, `P16-E003`) |
| Refundable projection | True net allocations enforce cumulative remaining and rebuild deterministic status/dependency/current/budget/project/goal projections; explicit excess remains auditable without making projection amounts negative | VERIFIED (`P16-E003`, `DL-071`) |
| Original dependency handling | Original trash atomically reverses linked refunds or converts them to independent according to complete per-dependent policies; unresolved original edits/removal remain blocked | VERIFIED (`P16-E002`, `P16-E003`, `DL-072`) |
| Application/privacy boundary | Typed request/snapshot/search APIs and the encrypted adapter delegate every write to `FinancialMutationCoordinator`; route carries only an optional stable transaction ID and the draft remains in memory | VERIFIED (`P16-E001`, `P16-E006`) |
| UI and screenshots | REC-015/016 cover all 8 YAML states at the width/font/locale/theme matrix; two Compose/token pixel-hash goldens freeze linked-light and excess-dark without visual-draft input | VERIFIED (`P16-E004`, `P16-E005`, `DL-073`) |

P16 remains `VERIFIED`. `P16_REFUND_MAPPING.md` records its exact domain/fact/application/query/UI boundary. Credit/installment completion, reports, physical purge and P18+ features remain in their owning later stages.

### P17 result (verified)

| Area | P17 result | Classification |
|---|---|---|
| Budget model and history | Natural-month/template currents, immutable revisions, typed category limits and signed adjustment facts preserve full version history and explicit optimistic concurrency | VERIFIED (`P17-E001`—`P17-E003`) |
| Hierarchy and rollover | Base-only total/root/child constraints, unclassified total usage, non-duplicated parent/child consumption and uncapped positive/negative rollover rebuild deterministically across 122 months | VERIFIED (`P17-E002`, `P17-E003`) |
| Daily available amount | Exact checked-integer calculation exposes base, rollover, adjustments, usage, nonzero future recurrence reservation and remaining-day divisor independently | VERIFIED (`P17-E002`—`P17-E004`) |
| Atomic application/data boundary | Month, template and adjustment writes use only `FinancialMutationCoordinator`; command receipts, current pointers, revisions, facts, projections and `localRevision` share one SQLCipher transaction | VERIFIED (`P17-E001`, `P17-E003`, `P17-E006`) |
| UI, accessibility and screenshots | BUD-001—008 cover all 23 YAML states under width/font/locale/theme matrices; two governed Compose/token full-pixel digests freeze the budget home and constraint editor | VERIFIED (`P17-E004`, `P17-E005`) |

P17 is `VERIFIED`. `P17_BUDGET_MAPPING.md` records the exact aggregate/schema/application/projection/UI boundary. Recurrence authoring, projects, goals, analytics, durable operation execution and all P18+ workflows remain in their owning stages.

### P18 result (verified)

| Area | P18 result | Classification |
|---|---|---|
| Project aggregate and reports | Row-versioned project current/audit state, one optional goal relation, base-currency budget, SELF_SHARE usage, refund restoration, settlement status, cash flow and recent/keyset-paged transactions are complete | VERIFIED (`P18-E001`—`P18-E004`) |
| Frozen project effects | Transaction-time monthly inclusion is stored in each ProjectEffect; transfers and loan principal are excluded while real interest/fees/penalties consume; later configuration changes never rewrite history | VERIFIED (`P18-E002`, `P18-E003`) |
| Goal reservation facts | Account-bound Goal current state, exact GoalMovement/GoalEffect planning, transaction SPEND, refund RESTORE, canonical hash, idempotency and row conflict all terminate at `FinancialMutationCoordinator` | VERIFIED (`P18-E001`—`P18-E003`) |
| Availability and completion | Checked `actual - reserved` may be negative with warning but no blocking or automatic reduction; RELEASE/KEEP/CONTINUE retain explicit history and never change actual account balance | VERIFIED (`P18-E002`—`P18-E004`) |
| UI, accessibility and screenshots | PRJ-001—006 and GOL-001—005 cover all 31 YAML states under width/font/locale/theme matrices; two governed Compose/token full-pixel digests freeze cash flow and underfunded goal detail | VERIFIED (`P18-E004`, `P18-E005`) |

P18 is `VERIFIED`. `P18_PROJECT_GOAL_MAPPING.md` records the exact aggregate/fact/application/query/UI boundary. Recurrence allocation, settlement authoring, cross-feature analytics, widgets, operations and all P19+ workflows remain in their owning stages.

### P19 result (verified)

| Area | P19 result | Classification |
|---|---|---|
| Credit profile and statements | Typed billing/due/time-zone rules, permanent and temporary limits, estimated/official statements, sealing, overdue/paid/positive-balance states and immutable revisions are complete | VERIFIED (`P19-E001`—`P19-E004`) |
| Credit accounting and allocation | Consumption increases liability and expense; repayment transfers asset to liability with zero income/expense and deterministic earliest/specified/unallocated allocation | VERIFIED (`P19-E002`, `P19-E003`) |
| Safety and automation | Actual debt blocks active overpayment, passive positive balances remain explicit, formal auto-bookkeeping checks five eligibility facts and occurrence idempotency, and candidate mode writes no facts | VERIFIED (`P19-E001`—`P19-E004`) |
| Atomic application/data boundary | Every repayment and reassignment reaches immutable facts only through `FinancialMutationCoordinator`; profile/official statement revisions and all synchronous credit projections share the encrypted transaction/revision boundary | VERIFIED (`P19-E001`, `P19-E003`, `P19-E006`) |
| UI, accessibility and screenshots | REC-014 and CRD-001—008 cover all 29 YAML states under width/font/locale/theme matrices; two governed Compose/token full-pixel digests freeze account and official-difference states | VERIFIED (`P19-E004`, `P19-E005`) |

P19 is `VERIFIED`. `P19_CREDIT_MAPPING.md` records the exact domain/fact/application/query/UI boundary. P23 retains only occurrence-engine scheduling integration; installments, loans, analytics, widgets and all P20+ workflows remain in their owning stages.

### P22 result (verified)

| Area | P22 result | Classification |
|---|---|---|
| Activities and participants | Exactly one active self participant, arbitrary external participants, ordered membership, settlement currency/date/project association and audited activity changes are complete | VERIFIED (`P22-E001`, `P22-E003`) |
| Exact allocation and accounting | Equal/fixed/percentage/weight, exclusion, tax/service-fee distribution and closed rounding use checked minor units; only self's share enters consumption/budget/project and every participant delta nets to zero | VERIFIED (`P22-E002`, `P22-E003`) |
| Settlement lifecycle | Self-involved payments use balanced coordinator-owned facts; external-to-external payments are pure immutable subledger records; partial/multiple payments are idempotent and historical payments survive source edits | VERIFIED (`P22-E002`, `P22-E003`) |
| Projection and additional settlement | Paid/owed/settled/net positions rebuild canonically at one local revision; post-settlement edits recompute the theoretical residual and expose only application-generated supplemental suggestions | VERIFIED (`P22-E003`) |
| UI, routes and screenshots | REC-011 and SET-001—008 cover all 27 YAML states using StableId-only routes, three languages, responsive/font/theme/accessibility matrices and three full-pixel Compose digests | VERIFIED (`P22-E001`, `P22-E004`, `P22-E005`) |

P22 is `VERIFIED`. `P22_SETTLEMENT_MAPPING.md` records the exact allocation/fact/application/projection/UI boundary. P23 retains recurrence/occurrence integration; P25/P34 retain cross-feature analytics, and no P23+ stage is promoted.

### P26 result (verified)

| Area | P26 result | Classification |
|---|---|---|
| Custom report lifecycle | Closed P25 `ReportSpec` drives preview/save/edit/copy; current pointers, immutable revisions and row-version conflicts persist in SQLCipher with no JSON/formula/SQL input | VERIFIED (`P26-E001`—`P26-E003`) |
| Multiple dashboards | Named dashboards support accessible add/remove/reorder and full/half metric card widths; invalid combinations are explained and never silently rendered | VERIFIED (`P26-E003`, `P26-E004`) |
| Deterministic methods | Five anomaly rules, three forecast modes and moving-average/trend/forecast series use explicit versions/date input and exact integer/decimal arithmetic; missing history is insufficient data, not zero | VERIFIED (`P26-E002`, `P26-E003`) |
| Schema migration | Schema v2 adds 12 normalized analytics configuration tables; registered non-destructive v1→v2 SQLCipher migration retains predecessor data, switches the contract hash and reopens cleanly | VERIFIED (`P26-E003`) |
| UI, routes and export boundary | ANA-006—010/013/014 cover all 16 states in three languages and responsive/font/theme/accessibility matrices; routes carry only StableId/closed keys; export stops at a typed payload for P29 | VERIFIED (`P26-E001`, `P26-E004`, `P26-E005`) |

P26 is `VERIFIED`. `P26_CUSTOM_ANALYTICS_MAPPING.md` records the complete AST/configuration/algorithm/Schema v2/UI boundary. Actual export file generation remains P29; P28 and later stages are not promoted.

### P27 result (verified)

| Area | P27 result | Classification |
|---|---|---|
| Typed map query | Current-month consumption defaults, four closed modes, amount/count weights, merchant/place aggregation, three presentations, full typed account/category/merchant/place/project/kind/amount filters, same-dimension OR chips with independent removal, cross-dimension AND and default transfer/repayment/loan exclusion are complete | VERIFIED (`P27-E001`—`P27-E004`) |
| R*Tree and scale | SQLCipher performs viewport R*Tree candidate selection, exact E7 containment, current-revision/fact filtering and database aggregation before returning at most 512 render nodes; the inherited typed radius service retains R*Tree plus Kotlin Haversine filtering | VERIFIED (`P27-E002`, `P27-E003`) |
| Historical semantics | Consumption/refund, all-expense, cash-flow and all-located values read immutable base-currency EconomicEffect/Posting/evidence; current FX never rewrites history | VERIFIED (`P27-E002`, `P27-E003`) |
| MapLibre and fallback | App-owned `LedgerMap` provides incremental source updates, clusters, sequential-teal heatmap, single points, point/cluster interaction, attribution and a graphically distinct optional user location; failure retains the same accessible location data | VERIFIED (`P27-E001`, `P27-E004`) |
| UI, routes and pixels | ANA-011/012 cover all 9 YAML states, three languages, responsive/font/theme boundaries, coordinate-masked semantics, category table, preview and opaque StableId-only drilldown; two production Compose digests freeze the contract | VERIFIED (`P27-E004`, `P27-E005`) |

P27 is `VERIFIED`. `P27_CONSUMPTION_MAP_MAPPING.md` records the query/index/SDK/UI/privacy boundary. P28 and later stages are not promoted.

### P28 result (verified)

| Area | P28 result | Classification |
|---|---|---|
| Streaming parsers | Apache Commons CSV 1.14.1 with explicit charset/BOM/ICU4J detection and FastExcel 0.20.2 sheet/row streaming preserve non-ASCII, exact numeric/date/shared-string/cached-formula values; legacy/corrupt/cancel fail typed and the 100,000-row suite is capped at 256 MiB | VERIFIED (`P28-E001`—`P28-E003`) |
| Encrypted staging and recovery | Seven-table independent SQLCipher staging persists raw/parsed rows, mappings, errors, duplicates, prepared commands and attachments; 256-row checkpoints support pause, safe cancel and crash retry while source handles and commit descriptors remain encrypted | VERIFIED (`P28-E002`, `P28-E004`) |
| Preparation and structured coverage | General mapping, missing entities, manual FX, explicit duplicate resolution and no-split rejection are complete; all 15 structured workbook entity kinds are dependency ordered and applied through existing typed ports | VERIFIED (`P28-E001`, `P28-E002`, `P28-E005`) |
| Atomic application and undo | Small imports use one coordinator-owned batch transaction; large/structured imports use a validated same-filesystem shadow exchange. Source fingerprints make replay idempotent; row 99,999 failure leaves the primary unchanged; audit/history and financial or structured whole-batch undo pass on SQLCipher | VERIFIED (`P28-E005`) |
| UI, routes and pixels | IMP-001—010 cover all 34 YAML states, three languages, responsive/font/theme boundaries, 100,000-row virtualized preview, masked sample semantics and opaque operation routes; two production Compose digests freeze the textual/token-derived contract | VERIFIED (`P28-E001`, `P28-E006`) |

P28 is `VERIFIED`. `P28_IMPORT_MAPPING.md` records the parser/staging/recovery/atomicity/UI boundary. P29 and later stages are not promoted.

### P29 result (verified)

| Area | P29 result | Classification |
|---|---|---|
| Ordinary export formats | Current-filter CSV, the complete 15-data-sheet workbook plus metadata, and prepared report CSV/XLSX/PDF/PNG use Commons CSV, FastExcel 0.20.2 and Android PdfDocument without a POI or backup-container path | VERIFIED (`P29-E001`—`P29-E003`) |
| Streaming and scale | Every source is keyset/page bounded; the API 36 512 MiB device completes 100,000-row CSV/XLSX, 12,000-row 267-page PDF and a complete 100,000-row image-source scan without OOM | VERIFIED (`P29-E002`, `P29-E003`) |
| Privacy and metadata | Closed fields exclude vault/account-secret data, card output is last-four only and location coordinates are explicit/default-off; schema/app versions, revisions, scope and the non-backup disclaimer accompany output | VERIFIED (`P29-E001`—`P29-E005`) |
| SAF and recovery | App-private generation, provider `.partial`/`.previous` publication, encrypted handles/descriptors, operation checkpoints, safe cancel/cleanup, crash recovery, conflict confirmation and typed permission/space/unavailable failures pass on devices | VERIFIED (`P29-E003`, `P29-E004`) |
| UI, routes and pixels | EXP-001—004 and ANA-010 cover all 10 EXP states, three languages, responsive/font/theme boundaries, opaque routes, external-app failure and operation-center mapping; two production Compose digests freeze the textual/token-derived contract | VERIFIED (`P29-E001`, `P29-E006`) |

P29 is `VERIFIED`. `P29_EXPORT_MAPPING.md` records the content/format/field/SAF/recovery/UI boundary. P30 and later stages are not promoted.

### P30 result (verified)

| Area | P30 result | Classification |
|---|---|---|
| Managed repository | Versioned authenticated headers, immutable COMPLETE manifests, fixed SQLCipher chunks, attachment reuse, ordered references, configurable retention and reference-only GC provide logical-full/physical-incremental snapshots | VERIFIED (`P30-E001`, `P30-E002`, `P30-E004`) |
| Portable encrypted backup | Apache Commons Compress ZIP64 is emitted under Tink Streaming AEAD and contains database, settings, attachments, history, portable key material and optional recovery-wrapped Vault material without whole-document buffering | VERIFIED (`P30-E002`, `P30-E003`, `P30-E005`) |
| Recovery and Vault security | Device and recovery envelopes use independent random Argon2id salts/parameters; password changes support future-only or accessible-history re-encryption; background Vault inclusion sees only ciphertext and requires a configured recovery password | VERIFIED (`P30-E001`—`P30-E003`) |
| SAF and Drive | Persisted SAF directories publish through recoverable temporary names; Google Identity `drive.file` and Drive REST v3 use a repository-specific folder, resumable sessions, Range recovery, object verification, final manifest publication and scoped stale-object GC | VERIFIED (`P30-E001`, `P30-E002`, `P30-E005`) |
| Scheduling and operation lifecycle | The first committed financial change per local day schedules at most one backup; durable encrypted state supports progress, safe cancellation, process restart and cleanup while platform payloads carry only `operationId` | VERIFIED (`P30-E002`, `P30-E004`) |
| UI, routes and pixels | BKP-001—007 and SYS-003 cover all 28 YAML states in zh-CN/en-US/ja-JP, responsive/font/theme/accessibility boundaries, password-redacted semantics, Operation Center linkage and two production Compose digests | VERIFIED (`P30-E001`, `P30-E006`) |

P30 is `VERIFIED`. `P30_BACKUP_MAPPING.md` records the repository/container/key/transport/scheduling/UI boundary. Restore, replacement and merge remain P31 and are not promoted.

### P31 result (verified)

| Area | P31 result | Classification |
|---|---|---|
| Authenticated replacement restore | Streaming AEAD/hash/length verification, registered migration, SQLCipher shadow rebuild and Journal/FK/subtype/projection validation precede a pre-restore safety snapshot and atomic live/key/settings/attachment/Vault exchange | VERIFIED (`P31-E001`—`P31-E003`) |
| Crash and failure recovery | PREPARED/FINALIZED markers, exact live sidecar/key/artifact safety copies and a `NonCancellable` exchange/rollback boundary prevent half-restored state across process death, ENOSPC and every injected exchange point; unreadable live bytes are recoverable | VERIFIED (`P31-E003`) |
| Three-way merge | Same-book/base-currency commit DAG merge selects the closest ancestor, never compares timestamps, requires explicit transaction-fork resolution and appends a coordinator-owned two-parent merge commit in the validated shadow | VERIFIED (`P31-E002`, `P31-E003`) |
| Controlled purge | Fully reversed/closed transaction chains are revalidated under maintenance inside the financial transaction; facts/history/attachment references are removed, blobs enter GC and an idempotent PURGE commit plus payload-free tombstone advances atomically | VERIFIED (`P31-E004`) |
| Clear and cloud-delete boundaries | App-owned local data, backup/safety/operation artifacts and keys are removed only after work cancellation while user SAF/Drive data survives; CLR-002 reauthenticates and deletes manifest first then unreferenced Drive objects | VERIFIED (`P31-E002`, `P31-E005`) |
| UI, routes and pixels | RST-001—007/CLR-002, final JRN-012 and G-004/G-005 integration cover all frozen states, three languages, responsive/font/theme/accessibility boundaries, redacted password semantics and tombstone non-resurrection | VERIFIED (`P31-E004`—`P31-E006`) |

P31 is `VERIFIED`. `P31_RESTORE_MERGE_PURGE_MAPPING.md` records the authenticated materialization/shadow/exchange/merge/purge/clear/UI boundary. P32 and later stages are not promoted.

### P33 result (verified)

| Area | P33 result | Classification |
|---|---|---|
| Glance widgets | One Glance provider/configuration Activity supports the nine frozen types; launcher rendering reads only four bounded SQLCipher snapshot projections and quick entry opens a validated complete form without committing | VERIFIED (`P33-E001`—`P33-E004`) |
| Snapshot/privacy lifecycle | Schema v3 extends the governed widget projections with date/month/budget/available/selection fields; foreground date boundaries rebuild only those projections transactionally. Amounts default hidden per widget and app lock is not consulted for already-authorized launcher content | VERIFIED (`P33-E002`—`P33-E004`) |
| Navigation and remaining settings | The single grouped More hub reaches every non-bottom capability without a drawer; appearance/language-region/currency/calendar/trash/about and the transfer hub reuse governed components and persisted settings | VERIFIED (`P33-E001`, `P33-E005`) |
| Operations and notifications | The encrypted operation center exposes all durable states without parameters; safe cancel is checkpointed, long-task notifications deep-link to G-007 and every Worker input remains exactly `operationId` | VERIFIED (`P33-E001`, `P33-E006`, `P33-E007`) |
| Compatibility and localization | WGT-001—003 pass on API 28 and API 36; G/SETG/TRF/SYS flows and all 26 exact P33 states pass on API 36 in zh-CN/ja-JP/en-US | VERIFIED (`P33-E004`, `P33-E005`) |

P33 is `VERIFIED`. `P33_WIDGET_MORE_SETTINGS_MAPPING.md` records widget/snapshot/privacy/navigation/operation/settings ownership. P34 and later stages are not promoted.

## Coverage summary

| Baseline item | Count | State |
|---|---:|---|
| Requirements `REQ-001`—`REQ-090` | 90 | 73 requirements are `VERIFIED`; 17 are `IN_PROGRESS`; none remain `NOT_STARTED` |
| YAML screens/modes/dialogs/system flows `G-001`—`WGT-003` | 215 | All 215 are `VERIFIED`; later whole-product replay remains P34/P36 |
| Architecture ADRs | 20 + ADR-007A | ADR-001—ADR-018 and ADR-020 plus ADR-007A are `VERIFIED`; ADR-019 remains `IN_PROGRESS` |
| UI ADRs | 12 | UI-ADR-002/007/008/009/010/011/012 are `VERIFIED`; UI-ADR-001/003/004/005/006 are `IN_PROGRESS` |
| Permanent domain invariants | 35 | 26 are `VERIFIED`, including settlement conservation/local-account/history invariants (`INV-022`—`INV-024`); 9 retain later evidence |
| Logical schema families | 12 | All 12 frozen physical Schema v1 families remain `VERIFIED`; P26 registers and device-verifies the normalized non-destructive analytics-configuration Schema v2 expansion |
| Projection families | 7 + search/geographic indexes | All seven projection families plus both indexes are `VERIFIED`; P33 closes bounded widget runtime and date-boundary refresh |
| Durable/staging/backup operation inventories | 4 groups | Import operation/staging/audit, export/backup runtime and restore/merge records, checkpoints and crash recovery are `VERIFIED` by P28—P31 |

## Stage progression

| Stage | Status | Evidence / entry condition |
|---|---|---|
| P00 | VERIFIED | `python3 scripts/validate_spec_baseline.py` passed; see `P00-E001`—`P00-E006` |
| P01 | VERIFIED | Frozen toolchain, all modules, exact dependency graph, debug/release assembly, locks, verification metadata, lint and tests passed; see `P01-E001`—`P01-E007` |
| P02 | VERIFIED | Repeatable aggregate/static/artifact gates, named rejection proofs, exact traceability and four local GMD suites pass; remote CI is separately `UNVERIFIED`; see `P02-E001`—`P02-E009` |
| P03 | VERIFIED | Typed IDs, complete checked arithmetic including abs/accumulation, exact money/FX/expression/time/period/formatting foundations and real-violation rejection pass; see `P03-E001`—`P03-E009` |
| P04 | VERIFIED | Complete token generation/mapping, governed components, 215-route/646-state contract, five stacks, static rules and API 28/API 36 UI matrices pass; see `P04-E001`—`P04-E011` |
| P05 | VERIFIED | Complete pure Kotlin aggregate/lifecycle/query/operation model, coordinator/application ports, typed analytics/transfer contracts, property tests and architecture/static gates pass; see `P05-E001`—`P05-E006` |
| P06 | VERIFIED | Deterministic 11-rule accounting planner, immutable reversal lifecycle, exact FX/effect/hash paths, idempotency/conflict checks and 25 accounting-core invariant mappings pass; see `P06-E001`—`P06-E006` |
| P07 | VERIFIED | Complete Room/SQLCipher Schema v1, independent encrypted staging, migrations, capabilities, WAL/temp leakage and API 36 device contracts pass; see `P07-E001`—`P07-E006` |
| P08 | VERIFIED | One Room/SQLCipher transaction, coordinator-only write entry, synchronous projection rebuild/audit and typed keyset/FTS/R*Tree queries pass; see `P08-E001`—`P08-E006` |
| P09 | VERIFIED | Separate production key hierarchies, real SQLCipher book sessions, authenticated vault CryptoObjects, app-lock/privacy runtime and API 36 Keystore/device-credential tests pass; see `P09-E001`—`P09-E006` |
| P10 | VERIFIED | Encrypted streaming attachments, one-time pipe sharing, three-second foreground location, MapLibre fallback and all P10 required states pass on API 36; see `P10-E001`—`P10-E007` |
| P11 | VERIFIED | Single Activity/Compose root, complete SessionGate, five Navigation 3 stacks, typed Proto restore, secure empty-book onboarding and all 65 G/ONB states pass; see `P11-E001`—`P11-E008` |
| P12 | VERIFIED | Account/card/reference-data implementation, all 23 screen contracts and coordinator-owned atomic category/place batch edits pass; see `P12-E001`—`P12-E008` |
| P13 | VERIFIED | Complete category-first ordinary entry; `P13-E001`—`P13-E008` |
| P14 | VERIFIED | Transfer/opening/adjustment/FX evidence, valuation revision, currency settings and all five screen contracts; `P14-E001`—`P14-E008` |
| P15 | VERIFIED | Keyset/FTS journal, complete filters, bounded selection, immutable history/trash and all 12 JRN contracts; `P15-E001`—`P15-E008` |
| P16 | VERIFIED | Complete refund facts, allocations, dependency resolution and REC-015/016; `P16-E001`—`P16-E007` |
| P17 | VERIFIED | Complete monthly budget/template history, hierarchy constraints, signed adjustments, deterministic rollover/daily availability and BUD-001—008; `P17-E001`—`P17-E007` |
| P18 | VERIFIED | Complete project budgeting/reports, keyset transactions, goal reservations/completion and PRJ/GOL contracts; `P18-E001`—`P18-E007` |
| P19 | VERIFIED | Complete credit profile/statements, assignment, repayment/allocation, auto-bookkeeping boundary and REC-014/CRD-001—008; `P19-E001`—`P19-E007` |
| P20 | VERIFIED | Purchase stays whole; exact versioned schedules, explicit settlement/refund application, synchronized progress and REC-027/INS-001—006; `P20-E001`—`P20-E007` |
| P21 | VERIFIED | Combination contracts/tranches, exact versioned loan schedules, formal disbursement/payment, pure prepayment simulation, strict-future projection and all 15 LIA/REC/LOA destinations; `P21-E001`—`P21-E007` |
| P22 | VERIFIED | Exact mutual-expense allocation, unique-self activity/participants, immutable partial settlements, canonical positions/additional-settlement and REC-011/SET-001—008; `P22-E001`—`P22-E007` |
| P23 | VERIFIED | Immutable blueprints/series, deterministic occurrence engine, restricted-headless idempotent startup/WorkManager catch-up, fact-free candidates, formal credit/loan integration and all 11 REC/AUT destinations; `P23-E001`—`P23-E007` |
| P24 | VERIFIED | In-memory complete-row batch editing, one coordinator-owned SQLCipher commit, exact retry, whole-batch reversal, bounded query selection, 100,000-row virtualized UI and all five REC/JRN destinations; `P24-E001`—`P24-E007` |
| P25 | VERIFIED | Closed bounded ReportSpec AST, whitelist compiler, 20 fixed reports, twelve synchronous analytics rollups, nine integrity checks and all six ANA destinations; `P25-E001`—`P25-E007` |
| P26 | VERIFIED | Revisioned custom reports/dashboards/anomaly rules, exact deterministic forecast/derived series, non-destructive SQLCipher Schema v2 and all seven ANA destinations; `P26-E001`—`P26-E007` |
| P27 | VERIFIED | R*Tree-bounded consumption-map aggregation, immutable historical values, governed MapLibre cluster/heat/single rendering, accessible failure list and ANA-011/012; `P27-E001`—`P27-E007` |
| P28 | VERIFIED | Commons CSV/ICU and FastExcel streaming, independent SQLCipher staging, durable resume, explicit mapping/duplicates/FX, all 15 structured kinds, coordinator/shadow atomic commit, audit/undo and IMP-001—010; `P28-E001`—`P28-E008` |
| P29 | VERIFIED | Current-filter CSV, 15-sheet XLSX, report CSV/XLSX/PDF/PNG, closed sensitive fields, bounded streaming, SAF atomic publication, durable UIDT/WorkManager execution and EXP-001—004/ANA-010; `P29-E001`—`P29-E007` |
| P30 | VERIFIED | Always-encrypted managed and portable backup, chunk/object reuse, retention/GC, recovery/Vault wrapping, SAF/Drive resumability, daily scheduling and BKP-001—007/SYS-003; `P30-E001`—`P30-E008` |
| P31 | VERIFIED | Authenticated bounded restore, SQLCipher shadow validation/atomic exchange, commit-graph merge, controlled purge/tombstone, clear/recovery integration and RST/JRN/CLR/G flows; `P31-E001`—`P31-E008` |
| P32 | VERIFIED | Per-action Vault authentication, ciphertext-only persistence, recovery rewrap, clipboard/screen privacy, closed-schema telemetry/ACRA, scoped clear and all VLT/SETG/CLR/SYS contracts pass; see `P32-E001`—`P32-E008` |
| P33 | VERIFIED | Nine snapshot-only Glance widgets, default-hidden per-widget privacy, full-form quick entry, one More/transfer/settings/help surface, durable operation center and notification deep links pass on API 28/API 36; see `P33-E001`—`P33-E008` |
| P34—P36 | NOT_STARTED | P34 is next; do not promote later work early |

## P17 verified handoff

P17 leaves the repository at a fully verified budget aggregate, coordinator, projection and UI boundary. The reproducible P17 commands are:

```text
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_p17_budget.py
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
./gradlew :finance:data:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.finance.data.BudgetApplicationPortDeviceTest --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
./gradlew :feature:planning:pixel6Api36DebugAndroidTest --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
./gradlew p17Check --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
```

All P17 evidence is recorded in `P17-E001`—`P17-E007`. The next entry point is P18; reuse the typed budget facts, exact projections and sole financial coordinator rather than introducing feature-owned SQL, mutable history or a second budget calculation path.

## P18 verified handoff

P18 leaves the repository with verified project/goal aggregates, immutable facts, synchronous projections, keyset Paging and all 11 UI contracts. The reproducible P18 commands are:

```text
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_p18_project_goal.py
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
./gradlew :finance:data:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.finance.data.ProjectGoalApplicationPortDeviceTest --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
./gradlew :feature:planning:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.feature.planning.ProjectGoalUiContractDeviceTest,app.ledger.feature.planning.P18GoldenDeviceTest --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
./gradlew p18Check --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
```

All P18 evidence is recorded in `P18-E001`—`P18-E007`. The next entry point is P19; reuse the coordinator-owned goal/project facts and exact projections without introducing mutable history, signed values hidden in positive-money fields or feature-owned financial SQL.

## P19 verified handoff

P19 leaves the repository with verified credit profiles/statements, immutable repayment/allocation facts, synchronous projections, guarded automatic bookkeeping and all nine UI contracts. The reproducible P19 commands are:

```text
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_p19_credit.py
./gradlew :finance:domain:test
./gradlew :finance:data:pixel6Api36DebugAndroidTest
./gradlew :feature:liabilities:pixel6Api36DebugAndroidTest
./gradlew :app:pixel6Api36DebugAndroidTest
./gradlew p19Check
```

All P19 evidence is recorded in `P19-E001`—`P19-E007`. The next entry point is P20; reuse the coordinator-owned credit facts and exact projections without inventing bank-payment success, minimum-payment data, mutable official differences or feature-owned financial SQL.

## P20 verified handoff

P20 leaves the repository with verified purchase-linked installment aggregates, immutable terms/schedules, exact principal/cost calculations, explicit settlement/refund application, synchronized progress and all seven UI contracts. The reproducible P20 commands are:

```text
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_p20_installments.py
./gradlew :finance:domain:test
./gradlew :finance:data:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.finance.data.InstallmentApplicationPortDeviceTest
./gradlew :feature:liabilities:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.feature.liabilities.InstallmentUiContractDeviceTest,app.ledger.feature.liabilities.P20GoldenDeviceTest
./gradlew :app:pixel6Api36DebugAndroidTest
./gradlew p20Check
```

All P20 evidence is recorded in `P20-E001`—`P20-E007`. The next entry point is P21; reuse the checked schedule/version/coordinator patterns without turning future installments into transactions, rewriting the original purchase or introducing feature-owned financial SQL.

## P21 verified handoff

P21 leaves the repository with verified combination-loan contracts, dedicated tranche ledgers, immutable terms/rate/schedule revisions, exact formal disbursement/payment accounting, isolated prepayment simulation, synchronous loan progress and future-only cash-flow projections, and all 15 UI contracts. The reproducible P21 commands are:

```text
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_p21_loans.py
./gradlew :finance:domain:test
./gradlew :finance:data:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.finance.data.LoanApplicationPortDeviceTest
./gradlew :feature:liabilities:pixel6Api36DebugAndroidTest
./gradlew :app:pixel6Api36DebugAndroidTest
./gradlew p21Check
```

All P21 evidence is recorded in `P21-E001`—`P21-E007`. The next entry point is P22; reuse typed StableId routes and coordinator-owned immutable facts without converting forecasts into transactions, rewriting schedule history or introducing feature-owned financial SQL.

## P22 verified handoff

P22 leaves the repository with exact closed allocation policies, unique-self activity/participant management, coordinator-owned expense and self-settlement facts, pure external-to-external subledger payments, immutable partial-payment history, deterministic position/suggestion rebuilds and all nine REC/SET destinations. The reproducible P22 commands are:

```text
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_p22_settlements.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts.tests.test_p22_settlement_contracts -v
./gradlew :finance:domain:test :finance:data:testDebugUnitTest :feature:record:testDebugUnitTest
./gradlew :finance:data:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.finance.data.SettlementApplicationPortDeviceTest
./gradlew :feature:settlement:pixel6Api36DebugAndroidTest
./gradlew :feature:record:pixel6Api36DebugAndroidTest
./gradlew :app:pixel6Api36DebugAndroidTest
./gradlew p22Check
```

All P22 evidence is recorded in `P22-E001`—`P22-E007`. The next entry point is P23; reuse the same idempotent occurrence/coordinator boundary without rewriting settlement history, turning candidates into facts or introducing a Worker-side financial DAO path.

## P23 verified handoff

P23 leaves the repository with immutable transaction blueprints, deterministic versioned recurrence rules/exceptions, unique occurrence reservation, startup plus unique WorkManager catch-up behind a `RECURRENCE_WRITE` headless lease, formal/candidate generation, atomic full-form candidate confirmation, credit/loan occurrence adapters and all 11 REC-026/AUT destinations. The reproducible P23 commands are:

```text
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_p23_automation.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts.tests.test_p23_automation_contracts -v
./gradlew :finance:domain:test :finance:data:testDebugUnitTest :feature:automation:testDebugUnitTest :app:testDebugUnitTest
./gradlew :finance:data:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.finance.data.AutomationApplicationPortDeviceTest
./gradlew :feature:automation:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.feature.automation.AutomationUiContractDeviceTest
./gradlew :feature:record:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.feature.record.OrdinaryRecordUiContractDeviceTest#rec026ContentAndEmptyRenderInRecordModuleAcrossAccessibilityBoundary
./gradlew :app:pixel6Api36DebugAndroidTest
./gradlew p23Check
```

All P23 evidence is recorded in `P23-E001`—`P23-E007`. The next entry point is P24. Preserve occurrence uniqueness and immutable blueprint/series revisions; never turn candidates into facts, acquire current location in recurrence execution, claim external payment success or add a Worker/feature financial DAO path.

## P24 verified handoff

P24 leaves the repository with a complete in-memory batch draft, governed virtualized summary/full-row/validation UI, safe StableId-only routes, a typed all-or-nothing application port, one coordinator-owned SQLCipher transaction, parent audit receipt, exact retry and immutable whole-batch reversal. JRN query selection remains fingerprint-plus-exclusions and its editor cannot represent amount, direction, refund relation or mutual-expense share. The reproducible P24 commands are:

```text
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_p24_batch.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts.tests.test_p24_batch_contracts -v
./gradlew :finance:domain:test :finance:data:testDebugUnitTest :feature:record:testDebugUnitTest :feature:journal:testDebugUnitTest :core:navigation:testDebugUnitTest
./gradlew :finance:data:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.finance.data.BatchEntryApplicationPortDeviceTest
./gradlew :feature:record:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.feature.record.BatchRecordUiContractDeviceTest
./gradlew :feature:journal:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.feature.journal.JournalUiContractDeviceTest
./gradlew :app:pixel6Api36DebugAndroidTest
./gradlew p24Check
```

All P24 evidence is recorded in `P24-E001`—`P24-E007`. The next entry point is P25. Preserve the sole `FinancialMutationCoordinator` write boundary, immutable parent/child audit identities, bounded selection specs and active-only FTS semantics; do not turn batch input into a persisted draft or a large-import bypass.

## P25 verified handoff

P25 leaves the repository with a bounded typed `ReportSpec` AST, a bound-parameter whitelist SQL compiler, the exact 20-report catalog, twelve revision-stamped daily/monthly projections, version-gated query/drilldown/export ports, a nine-check SQLCipher integrity/repair workflow, governed Vico wrappers and every ANA-001—005/015 required state. Original-currency subledger results retain explicit currency evidence; base-value measures never mix original and base minor units. Analytics adds no financial write path: synchronous projections remain below the existing `FinancialMutationCoordinator`, and repair is an explicit maintenance operation.

The reproducible P25 commands are:

```text
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_p25_analytics.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts.tests.test_p25_analytics_contracts -v
./gradlew :analytics:domain:test :analytics:data:testDebugUnitTest :feature:analysis:testDebugUnitTest :app:compileDebugKotlin
./gradlew :analytics:data:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.analytics.data.AnalyticsSqlCipherDeviceTest --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
./gradlew :feature:analysis:pixel6Api36DebugAndroidTest --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
./gradlew :app:pixel6Api36DebugAndroidTest --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
./gradlew p25Check --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
```

All P25 evidence is recorded in `P25-E001`—`P25-E007`. The next entry point is P26. Reuse the closed AST/compiler and versioned report results for custom reports/dashboards; do not add arbitrary SQL/formulas, persist sensitive query contents in routes/SavedState, bypass the encrypted application port, or reinterpret historical facts with current valuation.

## P26 verified handoff

P26 leaves the repository with normalized encrypted current/revision storage for custom reports, dashboards and anomaly rules; exact versioned anomaly/forecast/derived-series methods; safe StableId/closed-key routes; typed export payloads; governed Vico/data-table UI and every ANA-006—010/013/014 required state. Configuration edits do not create financial facts or advance `book.localRevision`, and feature code has no SQLCipher/DAO/Entity access.

The reproducible P26 commands are:

```text
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_p26_custom_analytics.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts.tests.test_p26_custom_analytics_contracts -v
./gradlew :analytics:domain:test :analytics:data:testDebugUnitTest :core:database:testDebugUnitTest :feature:analysis:testDebugUnitTest :app:compileDebugKotlin
./gradlew :core:database:pixel6Api36DebugAndroidTest --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
./gradlew :analytics:data:pixel6Api36DebugAndroidTest --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
./gradlew :feature:analysis:pixel6Api36DebugAndroidTest --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
./gradlew p26Check --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
```

All P26 evidence is recorded in `P26-E001`—`P26-E007`. P27 has subsequently completed the map surface. Preserve the typed report language, exact algorithm/version disclosures, normalized Schema v2, opaque route registry and P29 export ownership; do not introduce AI/OCR, user formulas/scripts, plaintext configuration or another financial writer.

## P27 verified handoff

P27 leaves the repository with a typed current-month consumption-map query, four accounting modes, amount/count weighting, merchant/place aggregation, same-dimension OR/cross-dimension AND filters with independently removable chips, R*Tree viewport candidates with exact E7 filtering, immutable historical base values and a 512-node render ceiling. The app-owned MapLibre host supports cluster/heat/single layers, viewport updates, point/cluster interaction, attribution and a distinct user-location graphic; failure retains the same data as an accessible list and drilldown. ANA-011/012 cover all nine frozen states with StableId-only navigation.

The reproducible P27 commands are:

```text
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_p27_consumption_map.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts.tests.test_p27_consumption_map_contracts -v
./gradlew :analytics:domain:test :core:geo:test :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest --tests app.ledger.app.ConsumptionMapFilterRemovalTest
./gradlew :analytics:data:pixel6Api36DebugAndroidTest
./gradlew :core:geo:pixel6Api36DebugAndroidTest
./gradlew :feature:analysis:pixel6Api36DebugAndroidTest
./gradlew :app:pixel6Api36DebugAndroidTest
./gradlew p27Check --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
```

All P27 evidence is recorded in `P27-E001`—`P27-E007`. The next entry point is P28. Preserve the R*Tree/query bound, immutable historical FX semantics, feature/application/SDK boundary and accessible fallback; do not create online place search, reverse geocoding, coordinate-bearing routes or another financial writer.

## P28 verified handoff

P28 leaves the repository with a nine-stage CSV/XLSX import flow plus history, bounded Commons CSV/ICU and FastExcel 0.20.2 streams, independently encrypted SQLCipher staging, durable foreground operation descriptors/checkpoints, explicit mappings/FX/duplicate decisions, and 15 dependency-ordered structured entity kinds. Small all-financial commits use the existing atomic batch port; large and structured commits use a validated shadow ledger and atomic exchange. Source fingerprints prevent replay, source row 99,999 failure cannot mutate the primary, and whole-batch undo remains auditable.

The reproducible P28 commands are:

```text
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_p28_import.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts.tests.test_p28_import_contracts -v
./gradlew :transfer:domain:test :transfer:data:testDebugUnitTest :finance:application:test
./gradlew :transfer:data:pixel2Api28DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.transfer.data.ImportParserCompatibilityDeviceTest
./gradlew :transfer:data:pixel6Api34DebugAndroidTest
./gradlew :transfer:data:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.transfer.data.ImportParserCompatibilityDeviceTest
./gradlew :finance:data:pixel6Api34DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.finance.data.ImportFinancialApplicationPortDeviceTest,app.ledger.finance.data.StructuredImportApplicationPortDeviceTest
./gradlew :feature:transfer:pixel6Api36DebugAndroidTest
./gradlew :app:pixel6Api36DebugAndroidTest
./gradlew p28Check --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
```

All P28 evidence is recorded in `P28-E001`—`P28-E008`. The next entry point is P29. Preserve the FastExcel/no-POI parser boundary, encrypted staging/source descriptors, operationId-only route/Worker payloads, explicit no-split and duplicate decisions, coordinator-only financial writes and validated atomic exchange; do not promote export/backup/restore stages early.

## P29 verified handoff

P29 leaves the repository with bounded ordinary export for the current journal filter, a complete 15-sheet business workbook and prepared reports in CSV/XLSX/PDF/PNG. SAF destinations publish through recoverable temporary names; encrypted handles/descriptors and durable operations survive process restart; coordinates remain default-off and vault secrets have no ordinary-export representation. Ordinary export is explicitly versioned and labeled as not being a complete backup.

The reproducible P29 commands are:

```text
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_p29_export.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts.tests.test_p29_export_contracts -v
./gradlew :transfer:domain:test :transfer:data:testDebugUnitTest :finance:application:test :app:testDebugUnitTest
./gradlew :transfer:data:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.transfer.data.StreamingExportScaleDeviceTest,app.ledger.transfer.data.SafExportDestinationDeviceTest
./gradlew :finance:data:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.finance.data.LedgerExportQueryDeviceTest
./gradlew :transfer:data:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.transfer.data.SqlCipherImportStagingDeviceTest#exportDescriptorAndReportCheckpointSurviveEncryptedRepositoryRecreation
./gradlew :app:pixel6Api34DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.app.ExportUidtSchedulingDeviceTest
./gradlew :feature:transfer:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.feature.transfer.ExportUiContractDeviceTest
./gradlew :app:pixel6Api36DebugAndroidTest
./gradlew p29Check p29Artifacts --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
```

All P29 evidence is recorded in `P29-E001`—`P29-E007`. The next entry point is P30. Preserve the ordinary-export/backup separation, closed sensitive allowlist, default-off coordinates, revision metadata, bounded sources, encrypted operation descriptors, operationId-only background payloads and recoverable SAF publication; do not reuse ordinary export as a backup container or promote P30+ work early.

## P30 verified handoff

P30 leaves the repository with an always-encrypted logical-full/physical-incremental backup repository, an independently recoverable ZIP64 portable container and resumable SAF/Drive publication. A snapshot becomes visible only after every encrypted object and checksum verifies and its manifest is published; unfinished objects remain collectible. The background path never decrypts Vault PAN/CVC data, and restore-password absence closes the Vault option.

The reproducible P30 commands are:

```text
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_p30_backup.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts.tests.test_p30_backup_contracts -v
./gradlew :transfer:domain:test :transfer:data:testDebugUnitTest --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
./gradlew :core:security:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.core.security.BackupKeyEnvelopeDeviceTest --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
./gradlew :app:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.app.ManagedBackupSqlCipherDeviceTest,app.ledger.app.BackupUidtSchedulingDeviceTest --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
./gradlew :transfer:data:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.transfer.data.SafBackupRepositoryDeviceTest --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
./gradlew :feature:transfer:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.feature.transfer.BackupUiContractDeviceTest --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
./gradlew p30Check p30Artifacts --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
```

All P30 evidence is recorded in `P30-E001`—`P30-E008`. The next entry point is P31. Preserve immutable final-manifest publication, authenticated object hashes, reference-only GC, independent recovery salts/parameters, background-ciphertext-only Vault handling, repository-scoped Drive access, persisted resumable checkpoints and operationId-only platform payloads; reuse these artifacts for restore verification without promoting P31 before its shadow rebuild/exchange and rollback gates pass.

## P31 verified handoff

P31 leaves the repository with bounded authenticated restore materialization, a clearable bounded password input, injected deterministic merge clocks, registered migration and complete SQLCipher shadow validation; crash-recoverable atomic database/key/settings/attachment/Vault exchange; closest-ancestor stable-ID three-way merge with explicit transaction-fork choices; and maintenance-locked, coordinator-owned closed-chain purge with a payload-free non-resurrection tombstone. G-004/G-005 and CLR-002 retain strict local/external/cloud authority boundaries.

The reproducible P31 commands are:

```text
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_p31_restore.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts.tests.test_p31_restore_contracts -v
./gradlew :transfer:domain:test :transfer:data:testDebugUnitTest :finance:domain:test :finance:application:test :app:testDebugUnitTest --no-configuration-cache --max-workers=2 --dependency-verification=strict --console=plain
./gradlew :transfer:data:testDebugUnitTest --tests app.ledger.transfer.data.RestoreMaterializerTest --no-configuration-cache --max-workers=1 -Dorg.gradle.jvmargs=-Xmx256m --dependency-verification=strict --console=plain
./gradlew :app:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.app.RestoreExchangeSqlCipherDeviceTest,app.ledger.app.MergeRestoreSqlCipherDeviceTest,app.ledger.app.LocalBookArtifactCleanerDeviceTest --no-configuration-cache --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g --dependency-verification=strict --console=plain
./gradlew :finance:data:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.finance.data.JournalApplicationPortDeviceTest --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
./gradlew :feature:transfer:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.feature.transfer.RestoreUiContractDeviceTest --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
./gradlew p31Check --no-configuration-cache --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g --dependency-verification=strict --console=plain
./gradlew p31Artifacts --no-configuration-cache --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g --dependency-verification=strict --console=plain
```

All P31 evidence is recorded in `P31-E001`—`P31-E008`. The next entry point is P32. Preserve the authenticated source/hash boundary, PREPARED/FINALIZED recovery markers, same-filesystem atomic move requirement, no-timestamp merge, forced tombstone precedence, purge revalidation and coordinator-only financial writes; do not weaken local/cloud deletion authority or promote P32+ work early.

## P32 verified handoff

P32 leaves the repository with a ciphertext-only SQLCipher card Vault, authentication-validity-zero Keystore wrapping and a fresh identity-bound `CryptoObject` for every reveal/copy/edit action. PAN clipboard content is marked sensitive and cleared on a 30-second timer/background; CVC has no copy API; all exposure/editor material clears on timeout, lock, background, leave and close. A recovery-wrapped Vault DEK is rebound to a fresh device-authentication KEK after restore without background card-field decryption.

Privacy diagnostics now use closed feature/crash schemas, separate consent/queues/128-bit IDs, 30-day rotation, 90/180-day retention, bounded atomic no-backup storage, a replaceable HTTPS whitelist sender with final string scan, ACRA 5.13.1 custom reports and `ApplicationExitInfo`. Settings cover app lock, screenshot/recent-task privacy, trash retention, diagnostic queue audit/delete, scoped authenticated local clear and device-security recovery. INV-032 is device-proven across SQLCipher, all ordinary workbook sheets, FTS, audit snapshots, telemetry/crash persistence and merged/unmerged Compose semantics.

The reproducible P32 commands are:

```text
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_p32_security_privacy.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts.tests.test_p32_security_privacy_contracts -v
./gradlew :core:security:testDebugUnitTest :core:telemetry:testDebugUnitTest :feature:vault:testDebugUnitTest :feature:settings:testDebugUnitTest --no-configuration-cache --max-workers=2 --dependency-verification=strict --console=plain
./gradlew :core:security:pixel6Api36DebugAndroidTest --no-configuration-cache --max-workers=2 --dependency-verification=strict --console=plain
./gradlew :core:telemetry:pixel6Api36DebugAndroidTest --no-configuration-cache --max-workers=2 --dependency-verification=strict --console=plain --no-daemon
./gradlew :finance:data:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.finance.data.LedgerExportQueryDeviceTest --no-configuration-cache --max-workers=2 --dependency-verification=strict --console=plain
./gradlew :feature:vault:pixel6Api36DebugAndroidTest :feature:settings:pixel6Api36DebugAndroidTest --no-configuration-cache --max-workers=2 --dependency-verification=strict --console=plain
./gradlew :app:pixel6Api36DebugAndroidTest --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain --no-daemon
./gradlew p32Check --no-configuration-cache --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2g --dependency-verification=strict --console=plain
./gradlew p32Artifacts --no-configuration-cache --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2g --dependency-verification=strict --console=plain
```

All P32 evidence is recorded in `P32-E001`—`P32-E008`. The next entry point is P33. Preserve the independent app/Vault gates, ciphertext-only data boundary, zero-free-text telemetry schemas, consent deletion semantics, `FLAG_SECURE`, sensitive-semantic exclusion and local/cloud authority split; do not expose Vault data through widgets or promote P33+ work early.

## P33 verified handoff

P33 leaves the repository with nine Glance widget types backed only by four bounded SQLCipher projections, per-widget default-hidden amount consent, closed key-unavailable/stale/no-data states and a quick-entry deep link that validates a category/template then opens the complete record form without writing. Foreground/open transitions refresh date-sensitive widget projections transactionally; launcher rendering never performs the refresh or complex SQL and never consults app-lock state.

The single grouped More surface reaches every non-bottom capability without a drawer. Transfer, encrypted durable operations, long-task notification deep links, offline allowlisted help and remaining appearance/language-region/currency/calendar/trash/about settings are integrated in zh-CN, ja-JP and en-US. Worker platform payloads remain exactly `operationId`.

The reproducible P33 commands are:

```text
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_p33_widget_navigation.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts.tests.test_p33_widget_navigation_contracts -v
./gradlew :widget:testDebugUnitTest :app:testDebugUnitTest :finance:data:testDebugUnitTest :transfer:data:testDebugUnitTest --no-configuration-cache --dependency-verification=strict --console=plain
./gradlew :core:database:pixel6Api36DebugAndroidTest --no-configuration-cache --dependency-verification=strict --console=plain
./gradlew :finance:data:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.finance.data.WidgetSnapshotApplicationPortDeviceTest --no-configuration-cache --dependency-verification=strict --console=plain
./gradlew :transfer:data:pixel6Api36DebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=app.ledger.transfer.data.SqlCipherImportStagingDeviceTest#durableOperationCenterListsNewestEncryptedOperationsWithoutParameters' --no-configuration-cache --dependency-verification=strict --console=plain
./gradlew :widget:pixel6Api36DebugAndroidTest --no-configuration-cache --dependency-verification=strict --console=plain
./gradlew :widget:pixel2Api28DebugAndroidTest --no-configuration-cache --dependency-verification=strict --console=plain
./gradlew :app:pixel6Api36DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.app.P33UiContractDeviceTest --no-configuration-cache --dependency-verification=strict --console=plain
./gradlew p33Check --no-configuration-cache --dependency-verification=strict --console=plain
./gradlew p33Artifacts --no-configuration-cache --dependency-verification=strict --console=plain
```

All P33 evidence is recorded in `P33-E001`—`P33-E008`. The next entry point is P34. Preserve the four-table snapshot-only launcher boundary, default-hidden per-widget permission, no app-lock inference, no quick-entry write, closed deep-link allowlists, operationId-only Worker payloads and the single More hub; do not promote P34+ work early.
