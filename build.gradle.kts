import org.gradle.api.artifacts.dsl.LockMode

plugins {
    id("ledger.architecture")
    alias(libs.plugins.spotless)
    alias(libs.plugins.kover)
    alias(libs.plugins.cyclonedx)
}

val koverProjects = subprojects.filter { project ->
    project.path != ":benchmark" && project.layout.projectDirectory.file("build.gradle.kts").asFile.isFile
}

koverProjects.forEach { project ->
    project.pluginManager.apply("org.jetbrains.kotlinx.kover")
}

val kspToolingVerification by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    description = "Resolves KSP's lazy compiler artifacts so strict verification metadata is reproducible."
}

val detektCli by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    description = "Pinned detekt CLI used without the Gradle-9-deprecated stable plugin adapter."
}

dependencies {
    kspToolingVerification(libs.ksp.aa.embeddable)
    kspToolingVerification(libs.ksp.tooling.coroutines)
    detektCli(libs.detekt.cli)
    koverProjects.forEach { kover(project(it.path)) }
}

val resolveKspToolingVerification by tasks.registering {
    group = "verification"
    description = "Resolves the exact KSP compiler classpath covered by dependency verification."
    inputs.files(kspToolingVerification)
    doLast {
        logger.lifecycle("KSP tooling verification classpath resolved.")
    }
}

allprojects {
    group = if (path == ":") {
        "app.ledger"
    } else {
        "app.ledger.${path.split(':').first(String::isNotBlank)}"
    }
    version = "0.2.0-p02"

    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
    }
}

val detektSources = fileTree(layout.projectDirectory) {
    include("app/src/**/*.kt")
    include("benchmark/src/**/*.kt")
    include("core/**/src/**/*.kt")
    include("finance/**/src/**/*.kt")
    include("analytics/**/src/**/*.kt")
    include("transfer/**/src/**/*.kt")
    include("feature/**/src/**/*.kt")
    include("widget/src/**/*.kt")
    include("build-logic/src/**/*.kt")
    exclude("**/build/**")
}

tasks.register<JavaExec>("detekt") {
    group = "verification"
    description = "Runs the pinned stable detekt CLI over all Kotlin production and test sources."
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    val reportDirectory = layout.buildDirectory.dir("reports/detekt")
    inputs.files(detektSources)
    inputs.file(layout.projectDirectory.file("config/detekt/detekt.yml"))
    outputs.files(
        reportDirectory.map { it.file("detekt.html") },
        reportDirectory.map { it.file("detekt.sarif") },
        reportDirectory.map { it.file("detekt.xml") },
    )
    doFirst { reportDirectory.get().asFile.mkdirs() }
    args(
        "--input",
        detektSources.files.joinToString(",") { it.absolutePath },
        "--config",
        layout.projectDirectory.file("config/detekt/detekt.yml").asFile.absolutePath,
        "--build-upon-default-config",
        "--parallel",
        "--jvm-target",
        "17",
        "--report",
        reportDirectory.map { "html:${it.file("detekt.html").asFile.absolutePath}" }.get(),
        "--report",
        reportDirectory.map { "sarif:${it.file("detekt.sarif").asFile.absolutePath}" }.get(),
        "--report",
        reportDirectory.map { "xml:${it.file("detekt.xml").asFile.absolutePath}" }.get(),
    )
}

spotless {
    kotlin {
        target(
            "app/src/**/*.kt",
            "benchmark/src/**/*.kt",
            "build-logic/src/**/*.kt",
            "core/*/src/**/*.kt",
            "finance/*/src/**/*.kt",
            "analytics/*/src/**/*.kt",
            "transfer/*/src/**/*.kt",
            "feature/*/src/**/*.kt",
            "widget/src/**/*.kt",
        )
        targetExclude(
            "**/GeneratedLedgerTokenContract.kt",
            "**/GeneratedScreenContract.kt",
        )
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target(
            "*.gradle.kts",
            "app/*.gradle.kts",
            "benchmark/*.gradle.kts",
            "build-logic/*.gradle.kts",
            "core/*/*.gradle.kts",
            "finance/*/*.gradle.kts",
            "analytics/*/*.gradle.kts",
            "transfer/*/*.gradle.kts",
            "feature/*/*.gradle.kts",
            "widget/*.gradle.kts",
        )
        ktlint(libs.versions.ktlint.get())
    }
}

tasks.register("p01Check") {
    group = "verification"
    description = "Runs the complete P01 build and architecture baseline checks."
    dependsOn("verifyArchitecture", "verifyFrozenVersions", resolveKspToolingVerification)
    dependsOn(subprojects.map { it.tasks.matching { task -> task.name == "assemble" } })
}

val validateP02Specs by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates the complete 90-requirement and 215-screen traceability contracts."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p02_quality.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        files(
            "docs/UI设计稿与实现契约_v1.0/android_ledger_ui_tokens_v1.json",
            "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
            "docs/UI设计稿与实现契约_v1.0/UI需求追踪矩阵_v1.csv",
        ),
    )
}

val validateP02SpecFixtures by tasks.registering(Exec::class) {
    group = "verification"
    description = "Proves the spec gate rejects missing requirements, screens, routes and ledger rows."
    workingDir(layout.projectDirectory)
    commandLine("python3", "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_*.py", "-v")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(fileTree("scripts") { include("**/*.py") })
}

tasks.register("p02Check") {
    group = "verification"
    description = "Runs the repeatable P02 build, test, static-analysis, architecture and spec gates."
    dependsOn(
        "p01Check",
        "spotlessCheck",
        "detekt",
        "verifySourcePolicies",
        validateP02Specs,
        validateP02SpecFixtures,
        gradle.includedBuild("build-logic").task(":test"),
    )
    dependsOn(subprojects.map { it.tasks.matching { task -> task.name == "lint" || task.name == "test" } })
}

tasks.register("p02Artifacts") {
    group = "verification"
    description = "Generates Kover XML/HTML, aggregate CycloneDX SBOM and OSS license reports."
    dependsOn("koverXmlReport", "koverHtmlReport", "cyclonedxBom", "generateLicenseReport")
}

val validateP03Core by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates the pure-Kotlin P03 money, time, ID and deterministic-algorithm baseline."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p03_core.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("core/common/src") { include("**/*.kt") },
        fileTree("core/money/src") { include("**/*.kt") },
        fileTree("core/time/src") { include("**/*.kt") },
        fileTree("finance/domain/src") { include("**/*.kt") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        files(".github/workflows/quality.yml"),
    )
}

tasks.register("p03Check") {
    group = "verification"
    description = "Runs P02 plus all P03 core tests, static policies and deterministic baseline checks."
    dependsOn("p02Check", validateP03Core)
}

tasks.register("p03Artifacts") {
    group = "verification"
    description = "Generates P03 coverage and the inherited auditable supply-chain artifacts."
    dependsOn("p02Artifacts")
}

val validateP04Generated by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fails when Kotlin token/routes or the token-only golden drift from the frozen textual inputs."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/generate_p04_contracts.py", "--check")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        "scripts/generate_p04_contracts.py",
        "docs/UI设计稿与实现契约_v1.0/android_ledger_ui_tokens_v1.json",
        "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
    )
    outputs.upToDateWhen { false }
}

val validateP04Ui by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates the complete P04 design-system, route, localization, golden and evidence contract."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p04_ui.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("core/designsystem/src") { include("**/*") },
        fileTree("core/navigation/src") { include("**/*.kt") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        fileTree("quality/screenshot") { include("*.md") },
        files(
            "docs/UI设计稿与实现契约_v1.0/android_ledger_ui_tokens_v1.json",
            "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
            "docs/UI设计稿与实现契约_v1.0/UI需求追踪矩阵_v1.csv",
        ),
    )
}

tasks.register("p04Check") {
    group = "verification"
    description = "Runs P03 plus all P04 design-system, navigation, golden, localization and static gates."
    dependsOn(
        "p03Check",
        validateP04Generated,
        validateP04Ui,
        ":core:designsystem:testDebugUnitTest",
        ":core:navigation:testDebugUnitTest",
    )
}

tasks.register("p04Artifacts") {
    group = "verification"
    description = "Generates P04-inclusive coverage and inherited auditable supply-chain artifacts."
    dependsOn("p03Artifacts")
}

val validateP05Domain by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates the complete pure-Kotlin P05 domain, command, lifecycle and port contract."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p05_domain.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("finance/domain/src") { include("**/*.kt") },
        fileTree("finance/application/src") { include("**/*.kt") },
        fileTree("analytics/domain/src") { include("**/*.kt") },
        fileTree("transfer/domain/src") { include("**/*.kt") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
    )
}

tasks.register("p05Check") {
    group = "verification"
    description = "Runs P04 plus the P05 domain/application tests, architecture policies and coverage gate."
    dependsOn(
        "p04Check",
        validateP05Domain,
        ":finance:domain:test",
        ":finance:application:test",
        ":analytics:domain:test",
        ":transfer:domain:test",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

tasks.register("p05Artifacts") {
    group = "verification"
    description = "Generates P05-inclusive coverage and inherited auditable supply-chain artifacts."
    dependsOn("p04Artifacts")
}

val validateP06Accounting by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates deterministic P06 accounting rules, immutable reversals and invariant evidence."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p06_accounting.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("finance/domain/src") { include("**/*.kt") },
        fileTree("finance/application/src") { include("**/*.kt") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
    )
}

tasks.register("p06Check") {
    group = "verification"
    description = "Runs P05 plus all P06 deterministic accounting, property, static and invariant gates."
    dependsOn(
        "p05Check",
        validateP06Accounting,
        ":finance:domain:test",
        ":finance:application:test",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

tasks.register("p06Artifacts") {
    group = "verification"
    description = "Generates P06-inclusive coverage and inherited auditable supply-chain artifacts."
    dependsOn("p05Artifacts")
}

val validateP07Generated by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fails when the checked-in P07 SQL schema catalogs drift from the exact SQL assets."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/generate_p07_schema_catalog.py", "--check")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        "scripts/generate_p07_schema_catalog.py",
        fileTree("core/database/src/main/assets") { include("*.sql") },
        "scripts/validate_spec_baseline.py",
    )
    outputs.upToDateWhen { false }
}

val validateP07Database by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates the complete P07 Room, SQLCipher, schema, migration and evidence contract."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p07_database.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("core/database/src/main") { include("**/*") },
        fileTree("core/database/schemas") { include("**/*.json") },
        fileTree("core/database/schema-contract") { include("**/*.json") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        "gradle/libs.versions.toml",
        "core/database/build.gradle.kts",
    )
}

tasks.register("p07Check") {
    group = "verification"
    description = "Runs P06 plus all P07 Room/SQLCipher schema, migration, static and JVM contract gates."
    dependsOn(
        "p06Check",
        validateP07Generated,
        validateP07Database,
        ":core:database:testDebugUnitTest",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

tasks.register("p07Artifacts") {
    group = "verification"
    description = "Generates P07-inclusive coverage and inherited auditable supply-chain artifacts."
    dependsOn("p06Artifacts")
}

val validateP08Data by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates the P08 atomic financial write, synchronous projection and typed query foundation."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p08_data.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("finance/application/src") { include("**/*.kt") },
        fileTree("finance/data/src") { include("**/*.kt") },
        fileTree("core/database/src/main") { include("**/*") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        fileTree("build-logic/src") { include("**/*.kt") },
    )
}

tasks.register("p08Check") {
    group = "verification"
    description = "Runs P07 plus P08 application/data tests, architecture policies and contract gates."
    dependsOn(
        "p07Check",
        validateP08Data,
        ":finance:application:test",
        ":finance:data:testDebugUnitTest",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

tasks.register("p08Artifacts") {
    group = "verification"
    description = "Generates P08-inclusive coverage and inherited auditable supply-chain artifacts."
    dependsOn("p07Artifacts")
}

val validateP09Security by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates the P09 Keystore/Tink/recovery-password, session, app-lock and privacy runtime."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p09_security.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("core/security/src") { include("**/*") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        fileTree("build-logic/src") { include("**/*.kt") },
        "gradle/libs.versions.toml",
        "core/security/build.gradle.kts",
    )
}

tasks.register("p09Check") {
    group = "verification"
    description = "Runs P08 plus P09 security unit, architecture, static-policy and contract gates."
    dependsOn(
        "p08Check",
        validateP09Security,
        ":core:security:testDebugUnitTest",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

tasks.register("p09Artifacts") {
    group = "verification"
    description = "Generates P09-inclusive coverage and inherited auditable supply-chain artifacts."
    dependsOn("p08Artifacts")
}

val validateP10FilesGeo by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates P10 encrypted attachments, foreground location, MapLibre and required UI states."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p10_files_geo.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("core/files/src") { include("**/*") },
        fileTree("core/geo/src") { include("**/*") },
        fileTree("feature/record/src") { include("**/*") },
        fileTree("core/security/src/main") { include("**/*.kt") },
        fileTree("finance/application/src/main") { include("**/*.kt") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
        "gradle/libs.versions.toml",
        "core/files/build.gradle.kts",
        "core/geo/build.gradle.kts",
    )
}

val p10Check = tasks.register("p10Check") {
    group = "verification"
    description = "Runs P09 plus all P10 JVM, static, lint, API 36 device/UI and contract gates."
    dependsOn(
        "p09Check",
        validateP10FilesGeo,
        ":core:files:testDebugUnitTest",
        ":core:geo:testDebugUnitTest",
        ":core:files:lintDebug",
        ":core:geo:lintDebug",
        ":feature:record:lintDebug",
        ":core:files:pixel6Api36DebugAndroidTest",
        ":core:geo:pixel6Api36DebugAndroidTest",
        ":feature:record:pixel6Api36DebugAndroidTest",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

gradle.projectsEvaluated {
    val filesDevice = project(":core:files").tasks.named("pixel6Api36DebugAndroidTest")
    val geoDevice = project(":core:geo").tasks.named("pixel6Api36DebugAndroidTest")
    val recordDevice = project(":feature:record").tasks.named("pixel6Api36DebugAndroidTest")
    geoDevice.configure { mustRunAfter(filesDevice) }
    recordDevice.configure { mustRunAfter(geoDevice) }
    p10Check.configure { dependsOn(filesDevice, geoDevice, recordDevice) }
}

tasks.register("p10Artifacts") {
    group = "verification"
    description = "Generates P10-inclusive coverage and inherited auditable supply-chain artifacts."
    dependsOn("p09Artifacts")
}

val validateP11AppShell by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates the P11 SessionGate, five-stack root, secure onboarding and exact UI-state evidence."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p11_app_shell.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("app/src") { include("**/*") },
        fileTree("feature/onboarding/src") { include("**/*") },
        fileTree("core/navigation/src") { include("**/*.kt") },
        fileTree("finance/application/src") { include("**/*.kt") },
        fileTree("finance/data/src") { include("**/*.kt") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
        "gradle/libs.versions.toml",
    )
}

tasks.register("p11Check") {
    group = "verification"
    description = "Runs P09 plus P11 JVM, static, lint, API 36 runtime/UI/golden and contract gates."
    dependsOn(
        "p09Check",
        validateP11AppShell,
        ":core:navigation:testDebugUnitTest",
        ":finance:application:test",
        ":feature:onboarding:testDebugUnitTest",
        ":app:lintDebug",
        ":feature:onboarding:lintDebug",
        ":app:pixel6Api36DebugAndroidTest",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

tasks.register("p11Artifacts") {
    group = "verification"
    description = "Generates P11-inclusive coverage and inherited auditable supply-chain artifacts."
    dependsOn("p09Artifacts")
}

val validateP12ReferenceData by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates implemented P12 account/reference-data scope and rejects a false VERIFIED promotion."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p12_reference_data.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("app/src") { include("**/*") },
        fileTree("core/designsystem/src") { include("**/*") },
        fileTree("feature/accounts/src") { include("**/*") },
        fileTree("feature/settings/src") { include("**/*") },
        fileTree("finance/application/src") { include("**/*.kt") },
        fileTree("finance/data/src") { include("**/*.kt") },
        fileTree("finance/domain/src") { include("**/*.kt") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
    )
}

tasks.register("p12Evidence") {
    group = "verification"
    description = "Runs non-promotional P12 evidence while two coordinator-owned batch rewrites remain incomplete."
    dependsOn(
        validateP12ReferenceData,
        "verifyArchitecture",
        "verifySourcePolicies",
        "spotlessCheck",
        "detekt",
        ":finance:domain:test",
        ":app:lintDebug",
        ":feature:accounts:lintDebug",
        ":feature:settings:lintDebug",
        ":finance:data:lintDebug",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

val validateP13Recording by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates the frozen P13 category-first ordinary-recording contract and VERIFIED evidence."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p13_recording.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("app/src") { include("**/*") },
        fileTree("core/designsystem/src") { include("**/*") },
        fileTree("core/files/src") { include("**/*.kt") },
        fileTree("feature/record/src") { include("**/*") },
        fileTree("finance/application/src") { include("**/*.kt") },
        fileTree("finance/data/src") { include("**/*.kt") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
    )
}

tasks.register("p13Check") {
    group = "verification"
    description = "Runs P13 static, JVM, lint and architecture evidence; managed-device suites run separately."
    dependsOn(
        validateP13Recording,
        "verifyArchitecture",
        "verifySourcePolicies",
        "spotlessCheck",
        "detekt",
        ":feature:record:testDebugUnitTest",
        ":app:lintDebug",
        ":feature:record:lintDebug",
        ":finance:data:lintDebug",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

val validateP14Multicurrency by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates the frozen P14 specialized-transaction, FX, valuation and currency-settings contract."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p14_multicurrency.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("app/src") { include("**/*") },
        fileTree("core/designsystem/src") { include("**/*") },
        fileTree("core/network/src") { include("**/*") },
        fileTree("feature/accounts/src") { include("**/*") },
        fileTree("feature/record/src") { include("**/*") },
        fileTree("feature/settings/src") { include("**/*") },
        fileTree("finance/application/src") { include("**/*.kt") },
        fileTree("finance/data/src") { include("**/*.kt") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
    )
}

tasks.register("p14Check") {
    group = "verification"
    description = "Runs P14 static, JVM, Lint and architecture evidence; managed-device suites run separately."
    dependsOn(
        validateP14Multicurrency,
        "verifyArchitecture",
        "verifySourcePolicies",
        "spotlessCheck",
        "detekt",
        ":core:network:testDebugUnitTest",
        ":feature:record:testDebugUnitTest",
        ":feature:settings:testDebugUnitTest",
        ":app:lintDebug",
        ":core:network:lintDebug",
        ":feature:accounts:lintDebug",
        ":feature:record:lintDebug",
        ":feature:settings:lintDebug",
        ":finance:data:lintDebug",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

val validateP15Journal by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates the frozen P15 journal, search, immutable history, dependency and trash contract."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p15_journal.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("app/src") { include("**/*") },
        fileTree("core/designsystem/src") { include("**/*") },
        fileTree("feature/journal/src") { include("**/*") },
        fileTree("finance/application/src") { include("**/*.kt") },
        fileTree("finance/data/src") { include("**/*.kt") },
        fileTree("finance/domain/src") { include("**/*.kt") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
    )
}

tasks.register("p15Check") {
    group = "verification"
    description = "Runs P15 static, JVM, Lint and architecture evidence; managed-device suites run separately."
    dependsOn(
        validateP15Journal,
        "verifyArchitecture",
        "verifySourcePolicies",
        "spotlessCheck",
        "detekt",
        ":finance:domain:test",
        ":finance:data:testDebugUnitTest",
        ":feature:journal:testDebugUnitTest",
        ":app:lintDebug",
        ":feature:journal:lintDebug",
        ":finance:data:lintDebug",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

val validateP16Refunds by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates the frozen P16 refund facts, projections, dependency policies and UI contract."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p16_refunds.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("app/src") { include("**/*") },
        fileTree("core/designsystem/src") { include("**/*") },
        fileTree("feature/record/src") { include("**/*") },
        fileTree("finance/application/src") { include("**/*.kt") },
        fileTree("finance/data/src") { include("**/*.kt") },
        fileTree("finance/domain/src") { include("**/*.kt") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
    )
}

tasks.register("p16Check") {
    group = "verification"
    description = "Runs P16 static, JVM, Lint and architecture evidence; managed-device suites run separately."
    dependsOn(
        validateP16Refunds,
        "verifyArchitecture",
        "verifySourcePolicies",
        "spotlessCheck",
        "detekt",
        ":finance:domain:test",
        ":finance:data:testDebugUnitTest",
        ":feature:record:testDebugUnitTest",
        ":app:lintDebug",
        ":feature:record:lintDebug",
        ":finance:data:lintDebug",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

val validateP17Budget by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates the frozen P17 budget hierarchy, rollover, adjustment, template and UI contract."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p17_budget.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("app/src") { include("**/*") },
        fileTree("core/designsystem/src") { include("**/*") },
        fileTree("feature/planning/src") { include("**/*") },
        fileTree("finance/application/src") { include("**/*.kt") },
        fileTree("finance/data/src") { include("**/*.kt") },
        fileTree("finance/domain/src") { include("**/*.kt") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
    )
}

tasks.register("p17Check") {
    group = "verification"
    description = "Runs P17 static, JVM, lint and architecture evidence; managed-device suites run separately."
    dependsOn(
        validateP17Budget,
        "verifyArchitecture",
        "verifySourcePolicies",
        "spotlessCheck",
        "detekt",
        ":finance:domain:test",
        ":finance:data:testDebugUnitTest",
        ":feature:planning:testDebugUnitTest",
        ":app:lintDebug",
        ":feature:planning:lintDebug",
        ":finance:data:lintDebug",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

val validateP18ProjectGoal by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates the frozen P18 project, goal-fund, projection, paging and UI contract."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p18_project_goal.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("app/src") { include("**/*") },
        fileTree("core/designsystem/src") { include("**/*") },
        fileTree("feature/planning/src") { include("**/*") },
        fileTree("finance/application/src") { include("**/*.kt") },
        fileTree("finance/data/src") { include("**/*.kt") },
        fileTree("finance/domain/src") { include("**/*.kt") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
    )
}

tasks.register("p18Check") {
    group = "verification"
    description = "Runs P18 static, JVM, lint and architecture evidence; managed-device suites run separately."
    dependsOn(
        validateP18ProjectGoal,
        "verifyArchitecture",
        "verifySourcePolicies",
        "spotlessCheck",
        "detekt",
        ":finance:domain:test",
        ":finance:data:testDebugUnitTest",
        ":feature:planning:testDebugUnitTest",
        ":app:lintDebug",
        ":feature:planning:lintDebug",
        ":finance:data:lintDebug",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

tasks.register<Exec>("generateLicenseReport") {
    group = "reporting"
    description = "Generates auditable CSV and HTML OSS inventories from the aggregate CycloneDX SBOM."
    dependsOn("cyclonedxBom")
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/generate_oss_licenses.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.file(layout.buildDirectory.file("reports/cyclonedx/bom.json"))
    outputs.files(
        layout.buildDirectory.file("reports/dependency-license/licenses.csv"),
        layout.buildDirectory.file("reports/dependency-license/index.html"),
    )
}
