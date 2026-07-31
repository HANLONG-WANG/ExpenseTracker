# Release Readiness

Last updated: 2026-08-01 (Asia/Tokyo)
Overall release status: `NOT_STARTED`  
P01 establishes a buildable module baseline only; no business page or release artifact is claimed.

## Quality gates

| Gate | Required evidence | Target phase | Status |
|---|---|---|---|
| Frozen build and module graph | JDK 17, AGP 9.3.x, Gradle 9.5.x, Kotlin 2.4.x, API 28/36; all prescribed modules and dependency rules | P01 | VERIFIED (`P01-E001`—`P01-E007`) |
| Reproducible quality infrastructure | CI, lint, detekt, formatting, Kover, dependency verification/locks, SBOM, license tasks, architecture tests | P02 | NOT_STARTED |
| Financial/domain correctness | Exact money/time algorithms, planners, coordinator-only writes, immutable facts and 35 invariants | P03/P05/P06/P08 | NOT_STARTED |
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

## Build-environment baseline after P01

Verified on this host: Temurin JDK 17.0.20, Android SDK Platform 36 revision 2, Build Tools 36.0.0, platform-tools/adb 37.0.1, Gradle Wrapper 9.5.1, AGP 9.3.1 and Kotlin 2.4.10. Recheck with:

```text
java -version
javac -version
sdkmanager --list_installed
adb version
```

Expected: Java/Javac 17, `platforms;android-36`, a stable `build-tools;36.x`, and a working adb. P02 may start by rerunning both baseline validators and the P01 aggregate Gradle command.

## P01 release conclusion

- Debug/release APK packaging is only build evidence for an intentionally page-free P01 shell; no AAB, signing, store upload, network deployment or external mutation is authorized or performed.
- No frozen specification is modified.
- Release readiness cannot be promoted by document completeness alone.
