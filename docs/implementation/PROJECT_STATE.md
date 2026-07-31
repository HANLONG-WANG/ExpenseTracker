# Project State

Last updated: 2026-07-31 (Asia/Tokyo)  
Current stage: P00 — specification audit and persistent execution ledgers  
Stage status: VERIFIED (`P00-E001`—`P00-E006`)  
Baseline Git commit: `7b8021d41a51070bbfa15e948614948ed6e17519`

## Recovery protocol after context compression

1. Read this file and the other six ledgers in `docs/implementation/`.
2. Run `python3 scripts/validate_spec_baseline.py` before changing implementation status.
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

## Repository inventory at P00 start

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

## Coverage summary

| Baseline item | Count | State |
|---|---:|---|
| Requirements `REQ-001`—`REQ-090` | 90 | All `NOT_STARTED`; baseline rows created only |
| YAML screens/modes/dialogs/system flows `G-001`—`WGT-003` | 215 | All `NOT_STARTED`; baseline rows created only |
| Architecture ADRs | 20 + ADR-007A | Registered; implementation `NOT_STARTED` |
| UI ADRs | 12 | Registered; implementation `NOT_STARTED` |
| Permanent domain invariants | 35 | Registered; verification `NOT_STARTED` |
| Logical schema families | 12 | Registered; implementation `NOT_STARTED` |
| Projection families | 7 + search/geographic indexes | Registered; implementation `NOT_STARTED` |
| Durable/staging/backup operation inventories | 4 groups | Registered; implementation `NOT_STARTED` |

## Stage progression

| Stage | Status | Evidence / entry condition |
|---|---|---|
| P00 | VERIFIED | `python3 scripts/validate_spec_baseline.py` passed; see `P00-E001`—`P00-E006` |
| P01 | BLOCKED | P00 is VERIFIED, but the required local JDK 17 and Android SDK 36 toolchain is unavailable |
| P02—P36 | NOT_STARTED | Do not promote early; follow the dependency graph in the preserved execution plan |

## P01 toolchain readiness

P00 itself needs no Android build or device tool. The current host does not yet satisfy P01's frozen build-tool prerequisite:

- Installed default runtime/compiler: OpenJDK 25.0.3 only; JDK 17 was not found under `/usr/lib/jvm`.
- Android Debug Bridge 35.0.2 is present.
- Android SDK command-line tools, SDK Platform 36 and Build Tools 36 were not found in the checked standard SDK locations; `sdkmanager` is not on `PATH`.
- No Gradle installation or wrapper exists; P01 is expected to create the frozen Gradle 9.5 wrapper after JDK/SDK readiness.

Required before executing P01:

1. Install a current patched JDK 17 distribution and select it for the project; verify with `java -version` and `javac -version` showing 17.
2. Install Android SDK Command-line Tools (current stable), SDK Platform 36 and a stable Build Tools 36.x package; verify with `sdkmanager --list_installed` and confirm `platforms;android-36` plus `build-tools;36.x`.
3. The frozen IDE recommendation is Android Studio Quail 2 if an IDE is used; command-line execution remains authoritative.
4. Resume at P01 by first running `python3 scripts/validate_spec_baseline.py`, then inventory the installed SDK/JDK and create the frozen module/build baseline.

Until those SDK-level tools are installed, P00 can be completed but P01 is `BLOCKED` from execution. This is a genuine environment prerequisite, not an application-code gap.
