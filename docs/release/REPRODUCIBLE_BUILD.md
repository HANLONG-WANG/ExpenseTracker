# Reproducible release build

## Frozen environment

- Temurin JDK 17
- Android SDK Platform 36 and Build Tools 36.0.0
- Gradle Wrapper 9.5.1, AGP 9.3.1, Kotlin 2.4.10
- Python 3 with PyYAML 6.0.3
- Linux host with KVM for the API 28/API 36 emulator evidence authorized for this delivery

All dependency versions are exact, all configurations use strict dependency locking, and `gradle/verification-metadata.xml` verifies resolved artifacts. Build from a clean checkout without editing frozen specifications:

```text
export SOURCE_DATE_EPOCH=$(git log -1 --format=%ct)
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_spec_baseline.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/validate_p01_baseline.py
./gradlew p36Check p36Artifacts --no-configuration-cache --max-workers=2 -Dorg.gradle.jvmargs=-Xmx3g --dependency-verification=strict --console=plain
```

`p36Artifacts` creates the unsigned release-candidate AAB when no external signing values are supplied, then emits deterministic SBOM, license, NOTICE and hash manifests under `build/reports/`. Store upload requires all external inputs below. A partially supplied signing set fails configuration instead of falling back.

For the publisher-owned signed bundle, pass secrets outside the repository:

```text
./gradlew :app:bundleRelease p36ReleaseManifest \
  -PledgerApplicationId=the.approved.reverse.dns.id \
  -PledgerSigningStoreFile=/absolute/path/to/upload-key.jks \
  -PledgerSigningStorePassword=FROM_A_SECRET_STORE \
  -PledgerSigningKeyAlias=APPROVED_ALIAS \
  -PledgerSigningKeyPassword=FROM_A_SECRET_STORE \
  --dependency-verification=strict --console=plain
```

Never place these values in `gradle.properties`, source files, CI logs, or checked-in artifacts. Verify `build/reports/release/p36-artifacts.sha256`, then use `jarsigner -verify` or `apksigner verify` on publisher-signed outputs as appropriate. Play App Signing remains the distribution-key authority; the local key is the publisher-controlled upload key.
