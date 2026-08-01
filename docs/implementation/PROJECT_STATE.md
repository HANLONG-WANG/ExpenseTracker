# Project State

Last updated: 2026-08-01 (Asia/Tokyo)
Current stage: P02 — quality gates, test infrastructure and CI
Stage status: VERIFIED (`P02-E001`—`P02-E009`); P03 is the next unstarted stage
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
| JVM and Android test stack | JUnit 5, Kotest Property/assertions, MockK, coroutines-test, Turbine, AndroidX Test/JUnit4, Compose UI Test, Espresso, MockWebServer and Room MigrationTestHelper are centrally configured; JVM tests and API 28/API 36 device harnesses execute | VERIFIED (`P02-E002`, `P02-E008`) |
| Static quality | Android Lint, pinned stable detekt CLI, Spotless + ktlint and Kover are wired into repeatable root tasks | VERIFIED (`P02-E003`, `P02-E005`, `P02-E006`) |
| Architecture/privacy policy | Module/source boundaries, governed UI access, sensitive route/SavedState, generic telemetry maps, ordinary logging, nondeterminism and coordinator-only financial writes have unit-tested source rules plus a committed rejected fixture | VERIFIED (`P02-E003`, `P02-E004`) |
| Traceability | Complete token JSON, screen YAML and requirement/screen CSV ledgers are cross-checked: 434 scalar tokens, 90 REQs, 215 unique IDs/routes, 646 required states and 192 explicitly mapped screens; six mutation cases prove omissions/drift fail | VERIFIED (`P02-E001`) |
| Supply chain | 37 lockfiles, strict root and standalone build-logic verification metadata, aggregate CycloneDX 1.6 JSON/XML SBOM, CSV/HTML OSS inventory and Kover XML/HTML reports generate in normal strict mode | VERIFIED (`P02-E006`) |
| CI and performance base | GitHub Actions runs the aggregate/static/supply-chain jobs plus API 28/API 36 Managed Device matrix; `:benchmark` executes its Macrobenchmark/BaselineProfile rule contract on API 36, ProfileInstaller is packaged and JankStats is owned by `:core:designsystem` | VERIFIED as P02 infrastructure (`P02-E007`, `P02-E008`); real measurements/profile remain P35/P36 |
| Screenshot provenance | No screenshot or golden is fabricated for the page-free shell. Later baselines may be captured only from implemented Compose UI derived from textual contracts/tokens; excluded visual drafts remain unread | Correct for P02; see `DL-013` |

P02 is complete. `/dev/kvm` is accessible, Emulator 37.1.11 reports usable KVM 12, and the API 28 app, API 36 app and API 36 benchmark Managed Device tasks all passed with zero failed/skipped tests. See `P02-E008`.

## Coverage summary

| Baseline item | Count | State |
|---|---:|---|
| Requirements `REQ-001`—`REQ-090` | 90 | `REQ-083`—`REQ-087` and `REQ-090` are `IN_PROGRESS` for their P02 verification bases; the other 84 remain `NOT_STARTED` |
| YAML screens/modes/dialogs/system flows `G-001`—`WGT-003` | 215 | All `NOT_STARTED`; baseline rows created only |
| Architecture ADRs | 20 + ADR-007A | ADR-001 `VERIFIED`; the other 20 decisions remain `NOT_STARTED` |
| UI ADRs | 12 | Registered; implementation `NOT_STARTED` |
| Permanent domain invariants | 35 | Registered; verification `NOT_STARTED` |
| Logical schema families | 12 | Registered; implementation `NOT_STARTED` |
| Projection families | 7 + search/geographic indexes | Registered; implementation `NOT_STARTED` |
| Durable/staging/backup operation inventories | 4 groups | Registered; implementation `NOT_STARTED` |

## Stage progression

| Stage | Status | Evidence / entry condition |
|---|---|---|
| P00 | VERIFIED | `python3 scripts/validate_spec_baseline.py` passed; see `P00-E001`—`P00-E006` |
| P01 | VERIFIED | Frozen toolchain, all modules, exact dependency graph, debug/release assembly, locks, verification metadata, lint and tests passed; see `P01-E001`—`P01-E007` |
| P02 | VERIFIED | Repeatable aggregate/static/artifact gates, rejection proofs, traceability checks, API 28/API 36 GMD and API 36 benchmark device tests pass; see `P02-E001`—`P02-E009` |
| P03—P36 | NOT_STARTED | P03 is the next execution stage; do not promote later work early |

## P03 entry state

P02 leaves the repository at a verified quality baseline. The completion commands were:

```text
/home/hubery-fedora/Tools/Android/Sdk/emulator/emulator -accel-check
./gradlew :app:pixel2Api28DebugAndroidTest --configuration-cache --no-parallel --console=plain
./gradlew :app:pixel6Api36DebugAndroidTest --configuration-cache --no-parallel --console=plain
./gradlew :benchmark:pixel6Api36DebugAndroidTest --configuration-cache --no-parallel --console=plain
./gradlew p02Check --configuration-cache --no-parallel --console=plain
./gradlew p02Artifacts --configuration-cache --no-parallel --console=plain
```

Acceleration, all three device commands and both aggregate tasks passed in `P02-E005`, `P02-E006` and `P02-E008`. P03 may start from this state, but no business requirement or screen is complete merely because its carrier/test infrastructure exists.
