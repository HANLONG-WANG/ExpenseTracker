# Test Evidence

Last updated: 2026-08-01 (Asia/Tokyo)
Evidence policy: a `VERIFIED` ledger row must cite an evidence ID below containing the exact command/test, environment and passing result. Implementation existence alone is not verification. Device-only claims cannot cite Robolectric or JVM-only evidence.

Remote CI policy: workflow parsing and local task execution prove only that CI is configured and callable. A remote GitHub Actions result may be marked verified only with a run URL/run ID and immutable artifact reference. No such remote evidence is present in this repository as of this update.

## Environment

- P01 starting commit: `cb4d66e581c1c5e55c02c64089a5461ac9bae249`
- Host: Fedora Linux, kernel `7.1.4-204.fc44.x86_64`
- Python: 3.14.6
- PyYAML: 6.0.3
- jq: 1.8.1
- Java/Javac: Eclipse Temurin 17.0.20+8
- Android SDK: Platform 36 revision 2; Build Tools 36.0.0
- adb/platform-tools: 37.0.1
- Build: Gradle Wrapper 9.5.1, AGP 9.3.1, Kotlin 2.4.10, KSP 2.3.10
- Emulator: 37.1.11; API 28 Google APIs x86/x86_64 and API 36 Google APIs x86_64 images installed
- Device acceleration: PASS — `/dev/kvm` is `crw-rw-rw- root:kvm 10,232`; emulator acceleration check reports `KVM (version 12) is installed and usable`
- Managed-device renderer: `android.testoptions.manageddevices.emulator.gpu=host`; local NVIDIA 610.43.03 OpenGL and KVM execution verified

## P00 evidence

| Evidence ID | Scope | Command / procedure | Expected result | Recorded result |
|---|---|---|---|---|
| P00-E001 | Frozen source parsing and structural completeness | `python3 scripts/validate_spec_baseline.py` | JSON, YAML and CSV parse; 434 token scalar paths; 90 sequential unique REQs; 215 unique screen IDs and routes | PASS, exit 0: 434 / 90 / 215 / 215 |
| P00-E002 | Manifest integrity and fail-closed visual exclusion | Same validator; every text read/hash enters through `assert_text_input`, and only the five textual UI inputs are hashed against `MANIFEST.sha256` | 5 text entries match; any forbidden visual filename is rejected before filesystem I/O | PASS: 5 textual entries matched; fail-closed visual access guard enabled; no fixed self-reported open count is used |
| P00-E003 | Persistent ledger exactness | Same validator plus `scripts/tests/test_p00_spec_baseline.py` | Exact ordered unique ADR/UI-ADR/INV/family sets; exact family→member mappings; frozen 94-table equality; duplicate/extra rows fail | PASS: exact 90 REQ, 215 screens, 35 INV, 20+1 ADR, 12 UI ADR, 94 logical tables, 12/7 families and 21 operation records; negative cases PASS |
| P00-E004 | Frozen text unchanged | Explicit `git diff --exit-code --` list containing only the ten allowed frozen textual inputs | Exit 0, no allowed frozen text changed; visual drafts are not included in the command | PASS, exit 0 |
| P00-E005 | Current repository baseline inventory | `git status --short`; source/config inventory excluding build caches and all four forbidden visual paths; `git diff --check`; `.pyc` scan | Current remediation changes are fully enumerated; no P04+ implementation, patch error or Python cache remains | PASS for current baseline; historical P00 command order remains explicitly non-reconstructed |
| P00-E006 | Validator syntax | `PYTHONPYCACHEPREFIX=/tmp/expense_tracker_p00_pycache python3 -m py_compile scripts/validate_spec_baseline.py` | Exit 0 | PASS, exit 0 |

Additional hygiene command: `git diff --check` and repository Python-cache scan passed on 2026-08-01 after remediation.

## P01 evidence

| Evidence ID | Scope | Command / procedure | Expected result | Recorded result |
|---|---|---|---|---|
| P01-E001 | Frozen host and Android toolchain | `java -version`; `javac -version`; `/home/hubery-fedora/Tools/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --list_installed`; `/home/hubery-fedora/Tools/Android/Sdk/platform-tools/adb version` | JDK/Javac 17; Android Platform 36; stable Build Tools 36.x; working adb | PASS: Temurin 17.0.20+8, Platform 36 rev 2, Build Tools 36.0.0, adb 37.0.1 |
| P01-E002 | Machine-readable P01 baseline | `PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_p01_baseline.py` | Exact module/version/lockfile/verification-coordinate checks; recursive nested production-source scan; no placeholder, forbidden stack, XML UI or runtime prerelease | PASS: 35 leaf modules, 5 grouping projects, 8 pure Kotlin modules, 12 feature modules, exact 37 lockfiles, 1,133 verification components, 2,339 SHA-256 entries, 0 forbidden findings |
| P01-E003 | Gradle module graph, external framework boundary and frozen versions | `./gradlew verifyArchitecture verifyFrozenVersions`; `python3 scripts/prove_p01_architecture_rejection.py` | Exact project graph; pure domain external Android/Room/Hilt/OkHttp/etc. dependencies rejected | PASS: current 40 root projects/edges accepted; injected domain OkHttp dependency rejected by `[ARCH-DOMAIN-FRAMEWORK]` |
| P01-E004 | Complete debug/release compilation and packaging | `./gradlew assemble --configuration-cache --console=plain` | All Android and pure-Kotlin modules configure and compile; app and benchmark debug/release variants package | PASS in 1m54s: 1,705 tasks, 1,308 executed, 392 from cache, 5 up-to-date |
| P01-E005 | Dependency locks and strict verification | Root and build-logic `LockMode.STRICT`; exact lock-coordinate validator; `python3 scripts/prove_p01_strict_lock_rejection.py`; normal strict aggregate build | Missing lock state must fail while the complete locked build passes | PASS: exact 37 lockfiles; all lock coordinates have verification metadata; injected resolvable unlocked configuration fails with Gradle strict-lock diagnostic |
| P01-E006 | Aggregate P01 build, lint and test | `./gradlew p03Check --configuration-cache --no-parallel --dependency-verification=strict --rerun-tasks --no-build-cache --console=plain` | Inherited P01 assemble/architecture/version/KSP tasks plus all later P02/P03 checks pass fresh | PASS in 44s: 2,110/2,110 actionable tasks executed; configuration cache stored |
| P01-E007 | Frozen-source and worktree hygiene regression | Four Python validators; explicit allowed frozen-text diff; `git diff --check`; recursive production placeholder and Python-cache scans | P00 baseline intact; no frozen text/placeholder/patch/cache defect | PASS, exit 0 for all checks; no visual-open count is inferred |

P01 intentionally contains no business implementation and therefore no device/UI/migration/screenshot/accessibility/performance case. Gradle `test` tasks completed successfully but were `NO-SOURCE`; they are recorded only as proof that the module test variants configure, not as behavioral verification. Device-only evidence remains mandatory in its listed later phase.

## P02 evidence

| Evidence ID | Scope | Command / procedure | Expected result | Recorded result |
|---|---|---|---|---|
| P02-E001 | Complete textual-contract and traceability gate | `PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_p02_quality.py`; Python unittest discovery | Exact full token/screen/matrix hashes, exact per-screen required-state mapping and exact 90/215 ledgers; equal-count drift rejected | PASS: 434/90/215/646/192 retained; complete hash gates pass; 13/13 total Python tests pass including equal-count state, non-sampled token and requirement-content mutations |
| P02-E002 | Frozen JVM/network/Android test infrastructure | Fresh aggregate JVM tests plus four GMD tasks | JUnit/Kotest/MockK/coroutines/Turbine/MockWebServer execute; Compose/Espresso, MigrationTestHelper and benchmark/profile harnesses execute | PASS: build logic 12/12; core testing 2/2; network 1/1; four device suites 4/4 |
| P02-E003 | Static architecture, privacy and write-gate policy | Fresh `detekt`, `spotlessCheck`, `verifySourcePolicies` and build-logic tests | Alias/type/scope-aware DAO writes; wrapper/alias-aware route/SavedState/telemetry/logging; full domain imports; all production source passes | PASS: detekt/Spotless/source gates exit 0; 12/12 policy engine tests pass |
| P02-E004 | Production-shaped rejection proof | Four proof wrappers over committed source and init-script fixtures | Every named fixture must fail due its own target diagnostic, including DAO alias, decoy Coordinator, route/state wrapper, telemetry/log alias and domain dependency | PASS: 14 source rule classes + 9 named P02 fixtures, 4 named money fixtures, domain-OkHttp init and missing-lock init all rejected |
| P02-E005 | Fresh aggregate quality gate | Same no-cache 2,110-task `p03Check` command as `P01-E006` | P01/P02 assemble/lint/test/detekt/Spotless/source/spec gates all execute, with failures propagated | PASS in 44s: 2,110/2,110 tasks executed |
| P02-E006 | Coverage and supply-chain artifacts under strict verification | `./gradlew p02Artifacts --configuration-cache --no-parallel --console=plain`; inspect generated JSON/XML/CSV/HTML | Normal mode, with dependency verification and locking active, generates aggregate Kover, CycloneDX and OSS inventory | PASS: 754 tasks; Kover XML/HTML; CycloneDX 1.6 JSON/XML with 736 components and 737 dependency nodes; license CSV/HTML with 736 rows (41 upstream metadata entries marked `UNKNOWN`); 37 lockfiles; root verification metadata has 1,133 components / 2,339 SHA-256 entries |
| P02-E007 | CI and callable API/performance entries | Parse `.github/workflows/quality.yml` with PyYAML; `./gradlew :app:tasks :benchmark:tasks :core:database:tasks --all --console=plain` | CI contains aggregate, all rejection proofs, artifact upload, API 28/API 36 GMD matrix and the API 36 MigrationTestHelper device task | LOCAL PASS: workflow/tasks are callable; remote Actions remains `UNVERIFIED` because no run URL/run ID/artifact reference is recorded |
| P02-E008 | Required local Managed Device execution | Fresh `--rerun-tasks --no-build-cache` API 28 app, API 36 app, API 36 benchmark and API 36 core:database GMD tasks | Compose/Espresso, performance-toolchain and MigrationTestHelper infrastructure execute on Emulator/KVM | PASS: four XML suites each 1/1 test; total 4 tests, 0 failures/errors/skipped |
| P02-E009 | Frozen-source and patch hygiene regression | Four Python validators; explicit ten-text frozen diff; `git diff --check`; `.pyc` scan | Frozen text unchanged; exact baselines coexist; no patch/cache defect | PASS: all validators and hygiene checks exit 0; 1,133 verification components / 2,339 SHA-256 entries; zero forbidden/runtime-prerelease/placeholder/cache findings |

P02 is `VERIFIED` for the repeatable local infrastructure and required local accelerated device entries only. Remote GitHub Actions execution remains `UNVERIFIED`; the workflow does not claim a remote pass. The 41 `UNKNOWN` upstream license metadata rows are retained visibly and do not satisfy or promote P36 NOTICE/release compliance.

## P03 evidence

Environment: Fedora host, Temurin JDK 17.0.20, Gradle 9.5.1, Kotlin 2.4.10, Asia/Tokyo. P03 production and behavioral tests execute only the pure Kotlin/JVM `:core:common`, `:core:money` and `:core:time` modules; they have no Android, Room or network dependency. Device evidence is neither required nor substituted for any platform behavior in this stage.

| Evidence ID | Scope | Command / procedure | Expected result | Recorded result |
|---|---|---|---|---|
| P03-E001 | Frozen baseline and complete P03 textual-contract query | Four Python validators with `PYTHONDONTWRITEBYTECODE=1` | Frozen text intact; exact 434/90/215/646 contracts, exact ledgers and five P03 mappings retained | PASS: all four validators exit 0; textual inputs are explicit allowlisted paths and visual drafts are excluded |
| P03-E002 | IDs, immutable results, typed errors and checked arithmetic | Fresh `:core:common:test`; inspect JUnit XML | Stable/Internal/Command/Revision typed IDs; checked add/subtract/multiply/negate/abs/accumulate and BigInteger fallback | PASS: 8/8 tests; addition and abs each use 1,000-case BigInteger oracle; `Long.MIN_VALUE` abs returns typed `ABS` overflow |
| P03-E003 | Money, legal-tender metadata, exact rounding, FX evidence/conversion and formatted UI model | `./gradlew :core:money:test --configuration-cache --no-parallel --console=plain`; inspect `MoneyAndCurrencyPropertyTest` and `FxAndFormattingTest` XML | Long minor units and per-currency scale; no crypto; checked sums; explicit rounding/MathContext; immutable quoted/fetched/provider/source/manual evidence; visible and privacy-safe hidden formatted models | PASS: 7/7 non-expression money/FX/formatting tests, zero failed/error/skipped; 1,000 generated Money additions; JPY/USD/KWD scales, overflow, currency mismatch, FX rounding and hidden-value non-leak pass |
| P03-E004 | Bounded deterministic amount expression language | Fresh `:core:money:test`; inspect `MoneyExpressionPropertyTest` XML | Required legal/illegal/full-width/locale/position/limit/extreme/rounding matrix | PASS: 11/11 expression tests; includes `÷`, isolated `)`, trailing operator, variable, scientific notation, `NUMBER_TOO_LONG`, token cap, extreme intermediate result and KWD rounding; money module 18/18 total |
| P03-E005 | Clock, effective time, storage keys and periods | Fresh `:core:time:test`; inspect JUnit XML | Gap defaults to reject; explicit shift carries adjustment provenance; overlap deterministic; all prior calendar/zone boundaries retained | PASS: 10/10 tests; default New York gap rejection and explicit 3,600-second adjustment evidence verified |
| P03-E006 | Pure-Kotlin and authoritative-money static rejection | Fresh build-logic tests, source gate, P03 validator and named fixture proof | No Float/Double or unchecked sum/fold/reduce/`+=`/manual Long accumulation; all named bypasses rejected | PASS: build logic 12/12; production policy clean; 4 named money fixtures rejected by binary-float/sum/accumulation rules |
| P03-E007 | Fresh inherited aggregate quality gate | No-cache 2,110-task `p03Check` command | Every inherited P00—P03 build/lint/test/spec/static gate executes | PASS in 44s: 2,110/2,110 tasks executed; configuration cache stored |
| P03-E008 | Coverage and inherited supply-chain artifacts | Fresh no-cache `p03Artifacts`; parse Kover XML, CycloneDX JSON and license CSV | Generate current P03-inclusive Kover, SBOM and OSS inventory | PASS: Kover 688/763 lines and 4,279/4,875 instructions; SBOM 736 components/737 dependency nodes; licenses 736 rows/41 visible UNKNOWN |
| P03-E009 | Final frozen-source, ledger and patch hygiene | Four Python validators; explicit allowed-text frozen diff; `git diff --check`; production placeholder/Python-cache scans; remediation tracker completeness check | All gates pass after ledger promotion; frozen text intact; no placeholder/cache/patch defect; all 215 screens remain unstarted | PASS: validators and hygiene checks exit 0; tracker 20/20 fixed; exact five P03 end-to-end rows remain `IN_PROGRESS`; INV-034/P00—P03 are truthfully `VERIFIED`; P04+ remains unstarted |

P03 is `VERIFIED`: exact typed ID/money/FX/expression/time/formatting foundations, checked abs, explicit DST-gap semantics and complete INV-034 static/property gates pass. This does not claim transaction persistence, offline FX repositories, financial planners or any Compose page; those remain P04 and later phases.

## P04 evidence

Environment: the same Fedora/JDK/Gradle/Android baseline above. Device cases run on locally accelerated Gradle Managed Devices: Pixel 2 API 28 Google APIs x86 and Pixel 6 API 36 Google APIs x86_64. P04 device tests exercise Compose rendering/semantics only; they do not substitute for later MapLibre, Glance, security or persistence device suites.

| Evidence ID | Scope | Command / procedure | Expected result | Recorded result |
|---|---|---|---|---|
| P04-E001 | Complete textual token/screen generation and mutation gates | `python3 scripts/generate_p04_contracts.py --check`; `python3 -m unittest scripts.tests.test_p04_ui_contracts -v`; inherited spec validators | Exact 434 scalar tokens, 215 unique routes, 646 states and target REQ rows; any unsampled token, unsafe route type, missing state or missing REQ changes/fails generation | PASS: 434/215/646; 4/4 P04 negative tests and inherited complete-hash gates pass |
| P04-E002 | Typed theme, contrast, dynamic boundary and chart compatibility | `./gradlew :core:designsystem:testDebugUnitTest`; inspect `LedgerTokenContractTest` XML | Typed values equal generated JSON; 16 category pairs, all semantic/chart cases and dynamic boundary pass contrast; pie 6/7 behavior deterministic | PASS: 8/8 tests, zero failures/errors/skips; generated token canonical SHA-256 retained |
| P04-E003 | Governed design-system API and Preview/localization foundation | Compile debug/main/androidTest; `python3 scripts/validate_p04_ui.py`; inspect 44 required component functions, closed models, resources and previews | All core wrappers/models/states, Vico/Map/Glance contracts, icon registry, zh/ja/en keys and 320/360/480 + 100/130/200% previews are present | PASS: complete source/contract validator and Android test compilation pass; no feature page or external SDK behavior claimed |
| P04-E004 | Navigation 3 route contract and five stacks | `./gradlew :core:navigation:testDebugUnitTest`; inspect `NavigationContractTest` XML | All YAML routes instantiate through typed arguments; no placeholders/raw public string payloads; five stacks, reselection, SessionGate and current-stack back pass | PASS: 5/5 tests, 215 IDs/routes and 646 required states, zero failures/errors/skips |
| P04-E005 | UI/privacy static governance and real rejection proof | `./gradlew :build-logic:test verifySourcePolicies`; `python3 scripts/prove_source_policy_rejection.py` | Production clean; feature Material/color/dp/theme/icon/swipe/duplicate/test-tag bypasses fail, with inherited privacy/write rules | PASS: 14/14 build-logic tests; 25 production files/215 screen IDs clean; 18 rule classes and 9 named fixtures rejected |
| P04-E006 | Formatting and static analysis | `./gradlew spotlessCheck detekt --no-configuration-cache --no-parallel --console=plain` | No ktlint/Detekt finding in production, test, preview, navigation or generator-support code | PASS, exit 0; generated sources are explicit deterministic formatting exclusions and remain hash-checked |
| P04-E007 | API 28 UI/golden/accessibility matrix | `./gradlew :core:designsystem:pixel2Api28DebugAndroidTest --no-configuration-cache --no-parallel --console=plain` | Token golden, hidden-value semantics, 48dp, size/font/theme/reduced-motion, map fallback and three locales render | PASS: 6/6 on Pixel 2 API 28, zero failures/errors/skips |
| P04-E008 | API 36 UI/golden/accessibility matrix | `./gradlew :core:designsystem:pixel6Api36DebugAndroidTest --no-configuration-cache --no-parallel --console=plain` | Same six cases on current target API, including token alpha-composition tolerance of at most one channel level | PASS: 6/6 on Pixel 6 API 36, zero failures/errors/skips |
| P04-E009 | Fresh cumulative P00—P04 aggregate | `./gradlew p04Check --configuration-cache --no-parallel --dependency-verification=strict --rerun-tasks --no-build-cache --console=plain` | All inherited assembly/lint/unit/static/spec gates plus P04 generation, design and navigation gates execute | PASS in 29s: 2,126/2,126 actionable tasks executed; configuration cache stored |
| P04-E010 | P04-inclusive auditable artifacts | `./gradlew p04Artifacts --configuration-cache --no-parallel --dependency-verification=strict --console=plain` after recording new preview POM SHA-256 metadata | Current Kover, CycloneDX and OSS license reports generate under strict locks/verification | PASS: full generation in 1m55s and normal strict replay in 8s (775 tasks); Kover XML/HTML (5,849/7,329 lines covered); CycloneDX 742 components/743 dependency nodes; license CSV/HTML 742 rows with 41 visible UNKNOWN entries |
| P04-E011 | Final ledger/frozen-source/worktree hygiene | P00/P01/P02/P03/P04 validators; generator `--check`; 17 Python mutation tests; explicit frozen-text diff; `git diff --check`; production placeholder/Python-cache scans; `spotlessCheck detekt verifySourcePolicies` | All ledgers match actual results; frozen specifications unchanged; 215 screens remain unstarted; no patch/cache/placeholder defect | PASS: all five validators, generator, 17/17 mutation tests and Gradle static gates passed on 2026-08-01; P01 reports 37 lockfiles, 1,143 verification components and 2,361 SHA-256 entries; frozen-text/diff checks exit 0; production placeholder and `.pyc` findings are zero; all 215 feature screens remain `NOT_STARTED` and P05+ is unstarted |

P04 verification is limited to the cross-cutting design, navigation and test contracts. It does not claim any of the 215 feature screen implementations, external MapLibre/Glance capability, database persistence, or completion of later accessibility acceptance across feature pages.

## Future mandatory evidence classes

| Class | Minimum acceptable evidence | Earliest phase |
|---|---|---|
| Build/static | Frozen Gradle aggregate task, lint, detekt, formatting, dependency locks/verification, architecture checks | P01/P02 |
| Domain | JUnit 5/Kotest property tests for all applicable accounting and deterministic invariants | P03/P06 |
| Database | Room migration helper plus actual-device SQLCipher/FTS5/R*Tree/WAL/integrity/projection evidence | P07/P08 |
| Security/device | Actual-device Keystore, BiometricPrompt, app lock, vault, clipboard and screenshot behavior | P09/P32 |
| Platform/device | Actual-device SAF, location timeout/permission and foreground/UIDT behavior | P10/P28—P31 |
| UI | Compose UI, screenshot/golden, three languages, width/font/theme matrix, TalkBack manual run and semantic leak audit | P04 and feature phases |
| Performance/fault | Macrobenchmark/profile, target-scale generators, query plans, streaming memory, cancellation, crash recovery and fault injection | P28—P35 |
| Release | Release AAB, Baseline Profile, SBOM, licenses/NOTICE, locks, signing/config checks and full acceptance report | P36 |

No future evidence is marked PASS in P00.
