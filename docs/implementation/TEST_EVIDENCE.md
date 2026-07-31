# Test Evidence

Last updated: 2026-08-01 (Asia/Tokyo)
Evidence policy: a `VERIFIED` ledger row must cite an evidence ID below containing the exact command/test, environment and passing result. Implementation existence alone is not verification. Device-only claims cannot cite Robolectric or JVM-only evidence.

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

## P00 evidence

| Evidence ID | Scope | Command / procedure | Expected result | Recorded result |
|---|---|---|---|---|
| P00-E001 | Frozen source parsing and structural completeness | `python3 scripts/validate_spec_baseline.py` | JSON, YAML and CSV parse; 434 token scalar paths; 90 sequential unique REQs; 215 unique screen IDs and routes | PASS, exit 0: 434 / 90 / 215 / 215 |
| P00-E002 | Manifest integrity without visual-draft reads | Same validator; it hashes only the five textual UI contract inputs and compares them with `MANIFEST.sha256` | 5 text entries match; 4 forbidden names are listed but their files are not opened | PASS: 5 textual entries matched; forbidden paths opened by validator: 0 |
| P00-E003 | Persistent ledger completeness | Same validator plus explicit CSV assertions | Exactly 90 requirement rows, 215 screen rows, ADR-001—020, ADR-007A, UI-ADR-001—012, INV-001—035, 94 logical tables, 12 schema families, 7 projection families and 21 operation/transfer records are present | PASS: all counts matched; 90 REQ and 215 screens remain `NOT_STARTED` |
| P00-E004 | Frozen files unchanged | `git diff --exit-code -- docs/规格冻结_v1.0 docs/UI设计稿与实现契约_v1.0` | Exit 0, no frozen tracked changes | PASS, exit 0 |
| P00-E005 | Repository baseline inventory | `git status --short --branch`; `rg --files` with all four forbidden paths excluded; explicit build/source/CI directory checks | Documentation-only baseline; preserved untracked plan; no Gradle/code/tests/resources/CI/build output | PASS; observations recorded in `PROJECT_STATE.md` |
| P00-E006 | Validator syntax | `PYTHONPYCACHEPREFIX=/tmp/expense_tracker_p00_pycache python3 -m py_compile scripts/validate_spec_baseline.py` | Exit 0 | PASS, exit 0 |

Additional hygiene command: `git diff --check` passed with exit 0 on 2026-07-31.

## P01 evidence

| Evidence ID | Scope | Command / procedure | Expected result | Recorded result |
|---|---|---|---|---|
| P01-E001 | Frozen host and Android toolchain | `java -version`; `javac -version`; `/home/hubery-fedora/Tools/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --list_installed`; `/home/hubery-fedora/Tools/Android/Sdk/platform-tools/adb version` | JDK/Javac 17; Android Platform 36; stable Build Tools 36.x; working adb | PASS: Temurin 17.0.20+8, Platform 36 rev 2, Build Tools 36.0.0, adb 37.0.1 |
| P01-E002 | Machine-readable P01 baseline | `PYTHONPYCACHEPREFIX=/tmp/expense_tracker_p01_pycache python3 -m py_compile scripts/validate_p01_baseline.py && python3 scripts/validate_p01_baseline.py` | Exact module/version/edge/lock/verification checks; complete JSON/YAML/CSV parse; no placeholder, forbidden stack, XML main UI or production-runtime prerelease | PASS, exit 0: 35 leaf modules, 5 grouping projects, 8 pure Kotlin modules, 12 feature modules, 37 lockfiles, 756 verification components, 1,253 SHA-256 entries, 434 token leaves, 90 requirements, 215 unique IDs/routes, 0 forbidden findings |
| P01-E003 | Gradle module graph and frozen versions | `./gradlew projects verifyArchitecture verifyFrozenVersions --configuration-cache --console=plain` | Every prescribed project and only its allowlisted direct project dependencies; Gradle/JDK/SDK/catalog/wrapper rules pass | PASS, exit 0: 35 leaf modules plus 5 grouping projects and included `:build-logic`; exact graph and frozen-version tasks passed |
| P01-E004 | Complete debug/release compilation and packaging | `./gradlew assemble --configuration-cache --console=plain` | All Android and pure-Kotlin modules configure and compile; app and benchmark debug/release variants package | PASS in 1m54s: 1,705 tasks, 1,308 executed, 392 from cache, 5 up-to-date |
| P01-E005 | Dependency locks and strict verification | `./gradlew assemble --write-locks --write-verification-metadata sha256`; `./gradlew -p build-logic dependencies --write-locks`; `./gradlew resolveKspToolingVerification --write-locks --write-verification-metadata sha256`; then run the normal aggregate command without write flags | Wrapper hash pinned; all resolvable project/build-logic/KSP tooling configurations locked; strict SHA-256 verification permits the normal build | PASS: wrapper distribution SHA-256 `bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f`; 37 lockfiles; 756 verified components and 1,253 SHA-256 entries; strict normal build passed |
| P01-E006 | Aggregate P01 build, lint, test and configuration-cache replay | `./gradlew p01Check lint test --configuration-cache --console=plain`, then repeat the identical command | Architecture/version/KSP verification, every assemble task, Android Lint and every configured JVM test task pass; second run reuses cache | PASS: first run 2m44s / 2,047 actionable tasks; repeat 665ms / 2,043 actionable tasks with 2,014 up-to-date and configuration cache reused |
| P01-E007 | Frozen-source and worktree hygiene regression | `python3 scripts/validate_spec_baseline.py`; `git diff --exit-code -- docs/规格冻结_v1.0 docs/UI设计稿与实现契约_v1.0`; `git diff --check` | P00 baseline still complete; no frozen specification changed; no patch whitespace errors | PASS, exit 0 for all checks; validator reports forbidden visual paths opened: 0 |

P01 intentionally contains no business implementation and therefore no device/UI/migration/screenshot/accessibility/performance case. Gradle `test` tasks completed successfully but were `NO-SOURCE`; they are recorded only as proof that the module test variants configure, not as behavioral verification. Device-only evidence remains mandatory in its listed later phase.

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
