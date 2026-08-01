# Project State

Last updated: 2026-08-02 (Asia/Tokyo)
Current stage: P06 — accounting planners, immutable facts and core invariants
Stage status: VERIFIED (`P06-E001`—`P06-E006`); P00—P06 are complete and P07 is the next unstarted stage
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
| Reproducibility | Wrapper distribution SHA-256 pinned; 37 lockfiles cover root, build logic and all leaf modules; strict dependency verification contains 756 components and 1,253 SHA-256 entries | VERIFIED |
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

## Coverage summary

| Baseline item | Count | State |
|---|---:|---|
| Requirements `REQ-001`—`REQ-090` | 90 | `REQ-085` is `VERIFIED`; 64 requirements are `IN_PROGRESS` for accurately scoped foundations; 25 remain `NOT_STARTED` |
| YAML screens/modes/dialogs/system flows `G-001`—`WGT-003` | 215 | All `NOT_STARTED`; baseline rows created only |
| Architecture ADRs | 20 + ADR-007A | ADR-001 `VERIFIED`; 17 decisions are `IN_PROGRESS` at typed-contract level; ADR-002/016/017 remain `NOT_STARTED` |
| UI ADRs | 12 | UI-ADR-002/007/010/011/012 are `VERIFIED`; UI-ADR-001/003/004/005/006/008 are `IN_PROGRESS`; UI-ADR-009 remains `NOT_STARTED` |
| Permanent domain invariants | 35 | `INV-034` `VERIFIED`; the other 34 are `IN_PROGRESS` at typed-model/policy foundation level and retain their later planner/database evidence |
| Logical schema families | 12 | Registered; implementation `NOT_STARTED` |
| Projection families | 7 + search/geographic indexes | Typed domain/query contracts `IN_PROGRESS`; physical projection/index implementation `NOT_STARTED` |
| Durable/staging/backup operation inventories | 4 groups | Typed Operation records and ports `IN_PROGRESS`; persistence/runtime implementation `NOT_STARTED` |

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
| P07—P36 | NOT_STARTED | P07 is the next execution stage; do not promote later work early |

## P07 entry state

P06 leaves the repository at a verified pure accounting planner boundary. Its completion commands are:

```text
python3 scripts/validate_p06_accounting.py
python3 -m unittest scripts.tests.test_p06_accounting_contracts -v
python3 scripts/prove_source_policy_rejection.py
./gradlew p06Check --configuration-cache --no-parallel --dependency-verification=strict --console=plain
./gradlew p06Artifacts --configuration-cache --no-parallel --dependency-verification=strict --console=plain
```

All commands and final hygiene gates pass in `P06-E001`—`P06-E006`. P07 may map these immutable plans and facts to the frozen Room/SQLCipher schema, but must preserve the same coordinator-owned atomic boundary, original fact/rule versions and exact hash/evidence domain. All 215 screen implementations remain `NOT_STARTED`; P07 must not infer page completion from planner capability.
