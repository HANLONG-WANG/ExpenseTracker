# Project State

Last updated: 2026-08-03 (Asia/Tokyo)
Current stage: P13 — Category-first ordinary recording and core entry experience
Stage status: VERIFIED (`P13-E001`—`P13-E008`); P00—P12 remain VERIFIED and P14 is the next unstarted stage
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
| Reproducibility | Wrapper distribution SHA-256 pinned; 37 lockfiles cover root, build logic and all leaf modules; strict dependency verification contains 1,357 components and 2,804 SHA-256 entries after the P12 application/reference-data graph and SBOM POMs were sealed | VERIFIED |
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
| UI evidence | 12 REC screens/42 states, widths/font scales, three languages, light/dark/reduced-motion, accessibility semantics and four exact Compose/token goldens pass on API 36 | VERIFIED (`P13-E004`, `P13-E005`) |

P13 is `VERIFIED`; P14 is `NOT_STARTED`. `P13_ORDINARY_RECORDING_MAPPING.md` records later ownership boundaries. No specialized P14+ transaction page, P18 goal behavior, P22 settlement management, P23 template authoring, P28 import flow or later acceptance stage is promoted.

## Coverage summary

| Baseline item | Count | State |
|---|---:|---|
| Requirements `REQ-001`—`REQ-090` | 90 | 20 requirements are `VERIFIED`; 58 are `IN_PROGRESS`; 12 remain `NOT_STARTED` |
| YAML screens/modes/dialogs/system flows `G-001`—`WGT-003` | 215 | 57 are `VERIFIED` (G-001—008, ONB-001—010, ATT-001—003, SYS-001, all 23 P12 screens and REC-001—012); 158 remain `NOT_STARTED` |
| Architecture ADRs | 20 + ADR-007A | ADR-001—ADR-010 and ADR-016/017 are `VERIFIED`; ADR-007A, ADR-011—015 and ADR-018—020 are `IN_PROGRESS` |
| UI ADRs | 12 | UI-ADR-002/007/010/011/012 are `VERIFIED`; UI-ADR-001/003/004/005/006/008 are `IN_PROGRESS`; UI-ADR-009 remains `NOT_STARTED` |
| Permanent domain invariants | 35 | `INV-034` `VERIFIED` remains the checked-arithmetic anchor; `INV-002`, `INV-005`, `INV-006`, `INV-007`, `INV-016`, `INV-029` and `INV-031` are also `VERIFIED`; 27 retain later evidence |
| Logical schema families | 12 | All 12 physical Schema v1 families `VERIFIED` by P07; P08 verifies normalized financial plan mapping and atomic repository behavior |
| Projection families | 7 + search/geographic indexes | Current transaction and settlement plus both indexes are `VERIFIED`; P08 subsets of the other families are verified while later-owned projections/runtime remain `IN_PROGRESS` |
| Durable/staging/backup operation inventories | 4 groups | Encrypted physical records `VERIFIED` by P07; operation runtime remains `IN_PROGRESS` for P28—P31 |

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
| P14—P36 | NOT_STARTED | P14 is next; do not promote later work early |

## P12 verified handoff

P12 leaves the repository at a fully verified account/reference-data boundary. The reproducible P12 commands are:

```text
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_p12_reference_data.py
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
./gradlew :app:pixel2Api28DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.ledger.app.P12UiContractDeviceTest --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
./gradlew :app:pixel6Api36DebugAndroidTest :finance:data:pixel6Api36DebugAndroidTest --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
./gradlew p12Evidence --no-configuration-cache --max-workers=1 --dependency-verification=strict --console=plain
```

All P13 evidence is recorded in `P13-E001`—`P13-E008`. The next entry point is P14; reuse the verified ordinary application adapter and sole financial coordinator boundary rather than adding a feature-owned persistence path.
