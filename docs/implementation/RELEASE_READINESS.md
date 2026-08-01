# Release Readiness

Last updated: 2026-08-01 (Asia/Tokyo)
Overall release status: `NOT_STARTED`  
P03 exact value/time foundations and the inherited P02 quality/device infrastructure are verified; no business page or release artifact is claimed.

## Quality gates

| Gate | Required evidence | Target phase | Status |
|---|---|---|---|
| Frozen build and module graph | JDK 17, AGP 9.3.x, Gradle 9.5.x, Kotlin 2.4.x, API 28/36; all prescribed modules and dependency rules | P01 | VERIFIED (`P01-E001`—`P01-E007`) |
| Reproducible quality infrastructure | CI, lint, detekt, formatting, Kover, dependency verification/locks, SBOM, license tasks, architecture tests and API 28/API 36 GMD entries | P02 | VERIFIED (`P02-E001`—`P02-E009`) |
| Financial/domain correctness | Exact money/time algorithms, planners, coordinator-only writes, immutable facts and 35 invariants | P03/P05/P06/P08 | IN_PROGRESS (`P03-E002`—`P03-E006` verify the exact ID/money/FX/expression/time base and INV-034; planners, persistence and 34 invariants remain) |
| Encrypted schema/migrations | Room 2.8.4 + SQLCipher 4.17.0, v1 export, migrations, FTS5/R*Tree, no destructive migration | P07 | NOT_STARTED |
| Security boundaries | Keystore/Tink/Argon2id, app/vault key separation, no sensitive route/state/log/telemetry leakage | P09/P32 | NOT_STARTED |
| Complete functionality | REQ-001—REQ-090 and all 215 screen contracts | P11—P34 | NOT_STARTED |
| Accessibility/localization/visual contract | Simplified Chinese, Japanese, English; themes/dynamic boundary; 320dp; 200%; TalkBack; reduced motion; non-color semantics | P34 | NOT_STARTED |
| Scale/performance/fault/security audit | 500k transactions, 2m facts/history, 100k import rows, 50k files, 10k places, tens-of-GB streams and frozen fault matrix | P35 | NOT_STARTED |
| Release artifact | Signed/configurable release AAB, Baseline Profile, dependency evidence, SBOM, licenses/NOTICE, privacy and build docs | P36 | NOT_STARTED |
| Final acceptance | 90 REQs VERIFIED, 215 screens VERIFIED, all release gates passing with API 28/API 36 physical-device evidence | P36 | NOT_STARTED |

## External release inputs

These are legitimate user/organization inputs, not Android code gaps. Their absence must not cause fake credentials or weaker implementations.

| Input | Needed for | Current status | Earliest blocking point |
|---|---|---|---|
| Final `applicationId`, Play listing identity and Play App Signing configuration | Store identity and production signing | NOT_STARTED — external input not yet supplied | P36 |
| Google Drive Authorization OAuth client bound to the final signing identity | Production Drive backup authorization | NOT_STARTED — external input not yet supplied | P30 production integration/P36 release |
| Self-hosted allowlisted telemetry/crash receiver URL, certificate and server retention policy | Production optional diagnostics transport | NOT_STARTED — external input not yet supplied | P32 production integration/P36 release |
| Official privacy-policy URL, source-repository URL, support contact and store assets | In-app/store disclosures | NOT_STARTED — external input not yet supplied | P36 |
| API 28 and API 36 physical devices plus chosen release-key custody process | Mandatory device regression and release security | NOT_STARTED — availability not yet confirmed | P35/P36 |
| Host KVM virtualization and current-user access to `/dev/kvm` | P02 Gradle Managed Device execution on API 28/API 36 | RESOLVED — KVM 12 usable and all three required GMD commands pass | P02 closed |

## Build-environment baseline after P03

Verified on this host: Temurin JDK 17.0.20, Android SDK Platform 36 revision 2, Build Tools 36.0.0, platform-tools/adb 37.0.1, Gradle Wrapper 9.5.1, AGP 9.3.1 and Kotlin 2.4.10. Recheck with:

```text
java -version
javac -version
sdkmanager --list_installed
adb version
```

Expected: Java/Javac 17, `platforms;android-36`, a stable `build-tools;36.x`, working adb and usable KVM for managed devices. All are present and verified.

## P01 release conclusion

- Debug/release APK packaging is only build evidence for an intentionally page-free P01 shell; no AAB, signing, store upload, network deployment or external mutation is authorized or performed.
- No frozen specification is modified.
- Release readiness cannot be promoted by document completeness alone.

## P02 release conclusion

- Static, test, CI, coverage and supply-chain infrastructure is implemented; strict dependency verification stays active when reports generate.
- The generated P02 SBOM/license/Kover files are build evidence, not P36 release artifacts or NOTICE approval; 41 upstream license metadata rows remain explicitly `UNKNOWN`.
- P02 is `VERIFIED` for local infrastructure: API 28/API 36 app GMD tests, API 36 benchmark toolchain and API 36 MigrationTestHelper tests execute successfully on KVM, in addition to local static/artifact gates.
- Remote GitHub Actions execution is `UNVERIFIED`: no run URL, run ID or immutable remote artifact reference is stored, so workflow configuration is not presented as a remote pass.

## P03 release conclusion

- P03 is `VERIFIED`: 36 pure-Kotlin core behavioral tests, 12 build-policy tests, the fresh 2,110-task `p03Check` gate and P03-inclusive Kover reports pass.
- Typed Command/Revision IDs, checked absolute value, default DST-gap rejection with explicit shift provenance and the expanded expression boundary matrix are verified.
- Authoritative money production paths contain no `Float`/`Double` or unchecked sum/fold/reduce/`+=`/manual-loop accumulation; four named fixtures prove each escape class is rejected.
- `INV-034` is verified. The other 34 permanent invariants, database facts, planners, offline FX caching and all UI acceptance remain later-phase work.
- All 215 screen rows remain `NOT_STARTED`; no screenshot, visual draft, APK behavior or release completeness is inferred from P03.
