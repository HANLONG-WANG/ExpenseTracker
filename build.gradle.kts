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

val validateP19Credit by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates the frozen P19 credit, statement, payment, projection and UI contract."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p19_credit.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("app/src") { include("**/*") },
        fileTree("core/designsystem/src") { include("**/*") },
        fileTree("feature/liabilities/src") { include("**/*") },
        fileTree("finance/application/src") { include("**/*.kt") },
        fileTree("finance/data/src") { include("**/*.kt") },
        fileTree("finance/domain/src") { include("**/*.kt") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
    )
}

val validateP19CreditFixtures by tasks.registering(Exec::class) {
    group = "verification"
    description = "Proves the P19 credit gate rejects coordinator, overpayment, statement, route and UI weakening."
    workingDir(layout.projectDirectory)
    commandLine("python3", "-m", "unittest", "scripts.tests.test_p19_credit_contracts", "-v")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        "scripts/validate_p19_credit.py",
        "scripts/tests/test_p19_credit_contracts.py",
        fileTree("app/src/main/kotlin") { include("**/*.kt") },
        fileTree("core/designsystem/src/main/kotlin") { include("**/*.kt") },
        fileTree("feature/liabilities/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/application/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/data/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/domain/src/main/kotlin") { include("**/*.kt") },
    )
}

tasks.register("p19Check") {
    group = "verification"
    description = "Runs P19 static, JVM, lint and architecture evidence; managed-device suites run separately."
    dependsOn(
        validateP19Credit,
        validateP19CreditFixtures,
        "verifyArchitecture",
        "verifySourcePolicies",
        "spotlessCheck",
        "detekt",
        ":finance:domain:test",
        ":finance:data:testDebugUnitTest",
        ":feature:liabilities:testDebugUnitTest",
        ":app:lintDebug",
        ":feature:liabilities:lintDebug",
        ":finance:data:lintDebug",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

val validateP20Installments by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates the frozen P20 installment, schedule, settlement, refund and UI contract."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p20_installments.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("app/src") { include("**/*") },
        fileTree("core/designsystem/src") { include("**/*") },
        fileTree("feature/liabilities/src") { include("**/*") },
        fileTree("finance/application/src") { include("**/*.kt") },
        fileTree("finance/data/src") { include("**/*.kt") },
        fileTree("finance/domain/src") { include("**/*.kt") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
    )
}

val validateP20InstallmentFixtures by tasks.registering(Exec::class) {
    group = "verification"
    description = "Proves the P20 gate rejects coordinator, conservation, revision, route and UI weakening."
    workingDir(layout.projectDirectory)
    commandLine("python3", "-m", "unittest", "scripts.tests.test_p20_installment_contracts", "-v")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        "scripts/validate_p20_installments.py",
        "scripts/tests/test_p20_installment_contracts.py",
        fileTree("app/src/main/kotlin") { include("**/*.kt") },
        fileTree("core/designsystem/src/main/kotlin") { include("**/*.kt") },
        fileTree("feature/liabilities/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/application/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/data/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/domain/src/main/kotlin") { include("**/*.kt") },
    )
}

tasks.register("p20Check") {
    group = "verification"
    description = "Runs P20 static, JVM, lint and architecture evidence; managed-device suites run separately."
    dependsOn(
        validateP20Installments,
        validateP20InstallmentFixtures,
        "verifyArchitecture",
        "verifySourcePolicies",
        "spotlessCheck",
        "detekt",
        ":finance:domain:test",
        ":finance:data:testDebugUnitTest",
        ":feature:liabilities:testDebugUnitTest",
        ":app:lintDebug",
        ":feature:liabilities:lintDebug",
        ":finance:data:lintDebug",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

val validateP21Loans by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates the frozen P21 loan, schedule, payment, simulation, projection and UI contract."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p21_loans.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("app/src") { include("**/*") },
        fileTree("core/designsystem/src") { include("**/*") },
        fileTree("feature/liabilities/src") { include("**/*") },
        fileTree("finance/application/src") { include("**/*.kt") },
        fileTree("finance/data/src") { include("**/*.kt") },
        fileTree("finance/domain/src") { include("**/*.kt") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
    )
}

val validateP21LoanFixtures by tasks.registering(Exec::class) {
    group = "verification"
    description = "Proves the P21 gate rejects coordinator, conservation, forecast, route and UI weakening."
    workingDir(layout.projectDirectory)
    commandLine("python3", "-m", "unittest", "scripts.tests.test_p21_loan_contracts", "-v")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        "scripts/validate_p21_loans.py",
        "scripts/tests/test_p21_loan_contracts.py",
        fileTree("app/src/main/kotlin") { include("**/*.kt") },
        fileTree("core/designsystem/src/main/kotlin") { include("**/*.kt") },
        fileTree("feature/liabilities/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/application/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/data/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/domain/src/main/kotlin") { include("**/*.kt") },
    )
}

tasks.register("p21Check") {
    group = "verification"
    description = "Runs P21 static, JVM, lint and architecture evidence; managed-device suites run separately."
    dependsOn(
        validateP21Loans,
        validateP21LoanFixtures,
        "verifyArchitecture",
        "verifySourcePolicies",
        "spotlessCheck",
        "detekt",
        ":finance:domain:test",
        ":finance:data:testDebugUnitTest",
        ":feature:liabilities:testDebugUnitTest",
        ":app:lintDebug",
        ":feature:liabilities:lintDebug",
        ":finance:data:lintDebug",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

val validateP22Settlements by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates the frozen P22 allocation, accounting, payment, projection, route and UI contract."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p22_settlements.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("app/src") { include("**/*") },
        fileTree("core/database/src/main") { include("**/*") },
        fileTree("core/designsystem/src") { include("**/*") },
        fileTree("feature/record/src") { include("**/*") },
        fileTree("feature/settlement/src") { include("**/*") },
        fileTree("finance/application/src") { include("**/*.kt") },
        fileTree("finance/data/src") { include("**/*.kt") },
        fileTree("finance/domain/src") { include("**/*.kt") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
    )
}

val validateP22SettlementFixtures by tasks.registering(Exec::class) {
    group = "verification"
    description = "Proves the P22 gate rejects coordinator, allocation, history, route and UI weakening."
    workingDir(layout.projectDirectory)
    commandLine("python3", "-m", "unittest", "scripts.tests.test_p22_settlement_contracts", "-v")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        "scripts/validate_p22_settlements.py",
        "scripts/tests/test_p22_settlement_contracts.py",
        fileTree("app/src/main/kotlin") { include("**/*.kt") },
        fileTree("core/designsystem/src/main/kotlin") { include("**/*.kt") },
        fileTree("feature/record/src/main/kotlin") { include("**/*.kt") },
        fileTree("feature/settlement/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/application/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/data/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/domain/src/main/kotlin") { include("**/*.kt") },
    )
}

tasks.register("p22Check") {
    group = "verification"
    description = "Runs P22 static, JVM, lint and architecture evidence; managed-device suites run separately."
    dependsOn(
        validateP22Settlements,
        validateP22SettlementFixtures,
        "verifyArchitecture",
        "verifySourcePolicies",
        "spotlessCheck",
        "detekt",
        ":finance:domain:test",
        ":finance:data:testDebugUnitTest",
        ":feature:record:testDebugUnitTest",
        ":feature:settlement:testDebugUnitTest",
        ":app:lintDebug",
        ":feature:record:lintDebug",
        ":feature:settlement:lintDebug",
        ":finance:data:lintDebug",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

val validateP23Automation by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates deterministic P23 templates, recurrences, candidates, Worker, routes and UI contracts."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p23_automation.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("app/src") { include("**/*") },
        fileTree("core/database/src/main") { include("**/*") },
        fileTree("core/designsystem/src") { include("**/*") },
        fileTree("feature/automation/src") { include("**/*") },
        fileTree("feature/record/src") { include("**/*") },
        fileTree("finance/application/src") { include("**/*.kt") },
        fileTree("finance/data/src") { include("**/*.kt") },
        fileTree("finance/domain/src") { include("**/*.kt") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
    )
}

val validateP23AutomationFixtures by tasks.registering(Exec::class) {
    group = "verification"
    description = "Proves the P23 gate rejects occurrence, candidate, Worker, route and design-system weakening."
    workingDir(layout.projectDirectory)
    commandLine("python3", "-m", "unittest", "scripts.tests.test_p23_automation_contracts", "-v")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        "scripts/validate_p23_automation.py",
        "scripts/tests/test_p23_automation_contracts.py",
        fileTree("app/src/main/kotlin") { include("**/*.kt") },
        fileTree("core/designsystem/src/main/kotlin") { include("**/*.kt") },
        fileTree("feature/automation/src/main/kotlin") { include("**/*.kt") },
        fileTree("feature/record/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/application/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/data/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/domain/src/main/kotlin") { include("**/*.kt") },
    )
}

tasks.register("p23Check") {
    group = "verification"
    description = "Runs P23 static, JVM, lint and architecture evidence; managed-device suites run separately."
    dependsOn(
        validateP23Automation,
        validateP23AutomationFixtures,
        "verifyArchitecture",
        "verifySourcePolicies",
        "spotlessCheck",
        "detekt",
        ":finance:domain:test",
        ":finance:data:testDebugUnitTest",
        ":feature:automation:testDebugUnitTest",
        ":core:navigation:testDebugUnitTest",
        ":app:lintDebug",
        ":feature:automation:lintDebug",
        ":feature:record:lintDebug",
        ":finance:data:lintDebug",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

val validateP24Batch by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates atomic P24 batch entry/edit, safe routes, virtualisation and coordinator boundaries."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p24_batch.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("app/src") { include("**/*") },
        fileTree("core/designsystem/src") { include("**/*") },
        fileTree("feature/record/src") { include("**/*") },
        fileTree("feature/journal/src") { include("**/*") },
        fileTree("finance/application/src") { include("**/*.kt") },
        fileTree("finance/data/src") { include("**/*.kt") },
        fileTree("finance/domain/src") { include("**/*.kt") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
    )
}

val validateP24BatchFixtures by tasks.registering(Exec::class) {
    group = "verification"
    description = "Proves the P24 gate rejects coordinator, route, virtualisation and bulk-field weakening."
    workingDir(layout.projectDirectory)
    commandLine("python3", "-m", "unittest", "scripts.tests.test_p24_batch_contracts", "-v")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        "scripts/validate_p24_batch.py",
        "scripts/tests/test_p24_batch_contracts.py",
        fileTree("app/src/main/kotlin") { include("**/*.kt") },
        fileTree("core/designsystem/src/main/kotlin") { include("**/*.kt") },
        fileTree("feature/record/src/main/kotlin") { include("**/*.kt") },
        fileTree("feature/journal/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/application/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/data/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/domain/src/main/kotlin") { include("**/*.kt") },
    )
}

tasks.register("p24Check") {
    group = "verification"
    description = "Runs P24 static, JVM, lint and architecture evidence; device suites run separately."
    dependsOn(
        validateP24Batch,
        validateP24BatchFixtures,
        "verifyArchitecture",
        "verifySourcePolicies",
        "spotlessCheck",
        "detekt",
        ":finance:domain:test",
        ":finance:data:testDebugUnitTest",
        ":feature:record:testDebugUnitTest",
        ":feature:journal:testDebugUnitTest",
        ":core:navigation:testDebugUnitTest",
        ":app:lintDebug",
        ":feature:record:lintDebug",
        ":feature:journal:lintDebug",
        ":finance:data:lintDebug",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

val validateP25Analytics by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates typed P25 reports, accounting semantics, integrity checks, safe routes and accessible UI."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p25_analytics.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("analytics") {
            include("**/*.kt", "**/*.kts", "**/AndroidManifest.xml")
            exclude("**/build/**")
        },
        fileTree("app/src") { include("**/*") },
        fileTree("core/database/src/main") { include("**/*") },
        fileTree("core/designsystem/src") { include("**/*") },
        fileTree("feature/analysis/src") { include("**/*") },
        fileTree("finance/application/src") { include("**/*.kt") },
        fileTree("finance/data/src") { include("**/*.kt") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
    )
}

val validateP25AnalyticsFixtures by tasks.registering(Exec::class) {
    group = "verification"
    description = "Proves the P25 gate rejects AST, SQL, projection, route, encryption and accessibility weakening."
    workingDir(layout.projectDirectory)
    commandLine("python3", "-m", "unittest", "scripts.tests.test_p25_analytics_contracts", "-v")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        "scripts/validate_p25_analytics.py",
        "scripts/tests/test_p25_analytics_contracts.py",
        fileTree("analytics") { include("**/*.kt") },
        fileTree("app/src/main/kotlin") { include("**/*.kt") },
        fileTree("core/database/src/main/kotlin") { include("**/*.kt") },
        fileTree("core/designsystem/src/main/kotlin") { include("**/*.kt") },
        fileTree("feature/analysis/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/application/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/data/src/main/kotlin") { include("**/*.kt") },
    )
}

tasks.register("p25Check") {
    group = "verification"
    description = "Runs P25 static, JVM, lint and architecture evidence; SQLCipher/UI device suites run separately."
    dependsOn(
        validateP25Analytics,
        validateP25AnalyticsFixtures,
        "verifyArchitecture",
        "verifySourcePolicies",
        "spotlessCheck",
        "detekt",
        ":analytics:domain:test",
        ":analytics:data:testDebugUnitTest",
        ":feature:analysis:testDebugUnitTest",
        ":app:lintDebug",
        ":analytics:data:lintDebug",
        ":feature:analysis:lintDebug",
        ":core:database:lintDebug",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

val validateP26CustomAnalytics by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates P26 revisioned custom reports, dashboards, deterministic anomaly/forecast engines, safe routes and accessible UI."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p26_custom_analytics.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("analytics") {
            include("**/*.kt", "**/*.kts", "**/AndroidManifest.xml")
            exclude("**/build/**")
        },
        fileTree("app/src") { include("**/*") },
        fileTree("core/database/src/main") { include("**/*") },
        fileTree("core/database/schemas") { include("**/*.json") },
        fileTree("core/designsystem/src") { include("**/*") },
        fileTree("feature/analysis/src") { include("**/*") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
    )
}

val validateP26CustomAnalyticsFixtures by tasks.registering(Exec::class) {
    group = "verification"
    description = "Proves the P26 gate rejects deterministic, encryption, revision, route and accessibility weakening."
    workingDir(layout.projectDirectory)
    commandLine("python3", "-m", "unittest", "scripts.tests.test_p26_custom_analytics_contracts", "-v")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        "scripts/validate_p26_custom_analytics.py",
        "scripts/tests/test_p26_custom_analytics_contracts.py",
        fileTree("analytics") { include("**/*.kt") },
        fileTree("app/src/main/kotlin") { include("**/*.kt") },
        fileTree("core/database/src/main/kotlin") { include("**/*.kt") },
        fileTree("feature/analysis/src/main/kotlin") { include("**/*.kt") },
    )
}

tasks.register("p26Check") {
    group = "verification"
    description = "Runs P26 static, JVM, lint and architecture evidence; SQLCipher/UI device suites run separately."
    dependsOn(
        validateP26CustomAnalytics,
        validateP26CustomAnalyticsFixtures,
        "verifyArchitecture",
        "verifySourcePolicies",
        "spotlessCheck",
        "detekt",
        ":analytics:domain:test",
        ":analytics:data:testDebugUnitTest",
        ":core:database:testDebugUnitTest",
        ":feature:analysis:testDebugUnitTest",
        ":app:lintDebug",
        ":analytics:data:lintDebug",
        ":feature:analysis:lintDebug",
        ":core:database:lintDebug",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

val validateP27ConsumptionMap by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates P27 RTree map queries, bounded MapLibre rendering, privacy, accessibility and safe routes."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p27_consumption_map.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("analytics/domain/src") { include("**/*") },
        fileTree("analytics/data/src") { include("**/*") },
        fileTree("app/src") { include("**/*") },
        fileTree("core/designsystem/src") { include("**/*") },
        fileTree("core/geo/src") { include("**/*") },
        fileTree("feature/analysis/src") { include("**/*") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
    )
}

val validateP27ConsumptionMapFixtures by tasks.registering(Exec::class) {
    group = "verification"
    description = "Proves the P27 gate rejects RTree, node-bound, exclusion, color, accessibility, route and geocoder weakening."
    workingDir(layout.projectDirectory)
    commandLine("python3", "-m", "unittest", "scripts.tests.test_p27_consumption_map_contracts", "-v")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        "scripts/validate_p27_consumption_map.py",
        "scripts/tests/test_p27_consumption_map_contracts.py",
        fileTree("analytics/domain/src") { include("**/*.kt") },
        fileTree("analytics/data/src") { include("**/*.kt") },
        fileTree("app/src/main/kotlin") { include("**/*.kt") },
        fileTree("core/designsystem/src/main/kotlin") { include("**/*.kt") },
        fileTree("core/geo/src/main/kotlin") { include("**/*.kt") },
        fileTree("feature/analysis/src/main/kotlin") { include("**/*.kt") },
    )
}

tasks.register("p27Check") {
    group = "verification"
    description = "Runs P27 static, JVM, lint and architecture evidence; SQLCipher/MapLibre/UI managed-device suites run separately."
    dependsOn(
        validateP27ConsumptionMap,
        validateP27ConsumptionMapFixtures,
        "verifyArchitecture",
        "verifySourcePolicies",
        "spotlessCheck",
        "detekt",
        ":analytics:domain:test",
        ":analytics:data:testDebugUnitTest",
        ":core:geo:test",
        ":feature:analysis:testDebugUnitTest",
        ":app:lintDebug",
        ":analytics:data:lintDebug",
        ":core:geo:lintDebug",
        ":feature:analysis:lintDebug",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

val validateP28Import by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates P28 streaming parsers, encrypted staging, atomic commits, recovery, privacy and UI coverage."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p28_import.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("app/src") { include("**/*") },
        fileTree("core/security/src") { include("**/*") },
        fileTree("feature/transfer/src") { include("**/*") },
        fileTree("finance/application/src") { include("**/*") },
        fileTree("finance/data/src") { include("**/*") },
        fileTree("transfer/domain/src") { include("**/*") },
        fileTree("transfer/data/src") { include("**/*") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
        "gradle/libs.versions.toml",
    )
}

val validateP28ImportFixtures by tasks.registering(Exec::class) {
    group = "verification"
    description = "Proves the P28 gate rejects parser, staging, atomicity, duplicate, split, privacy and entity-coverage weakening."
    workingDir(layout.projectDirectory)
    commandLine("python3", "-m", "unittest", "scripts.tests.test_p28_import_contracts", "-v")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        "scripts/validate_p28_import.py",
        "scripts/tests/test_p28_import_contracts.py",
        fileTree("app/src/main/kotlin") { include("**/*.kt") },
        fileTree("core/security/src/main/kotlin") { include("**/*.kt") },
        fileTree("feature/transfer/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/application/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/data/src/main/kotlin") { include("**/*.kt") },
        fileTree("transfer/domain/src/main/kotlin") { include("**/*.kt") },
        fileTree("transfer/data/src/main/kotlin") { include("**/*.kt") },
    )
}

tasks.register("p28Check") {
    group = "verification"
    description = "Runs P28 static, JVM, lint and architecture evidence; SQLCipher/runtime/UI managed-device suites run separately."
    dependsOn(
        validateP28Import,
        validateP28ImportFixtures,
        "verifyArchitecture",
        "verifySourcePolicies",
        "spotlessCheck",
        "detekt",
        ":transfer:domain:test",
        ":transfer:data:testDebugUnitTest",
        ":finance:application:test",
        ":app:lintDebug",
        ":core:security:lintDebug",
        ":feature:transfer:lintDebug",
        ":finance:data:lintDebug",
        ":transfer:data:lintDebug",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

val validateP29Export by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates P29 streaming exports, SAF publishing, recovery, sensitive-field exclusion and UI coverage."
    workingDir(layout.projectDirectory)
    commandLine("python3", "scripts/validate_p29_export.py")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        fileTree("scripts") { include("**/*.py") },
        fileTree("app/src") { include("**/*") },
        fileTree("core/security/src") { include("**/*") },
        fileTree("feature/analysis/src") { include("**/*") },
        fileTree("feature/transfer/src") { include("**/*") },
        fileTree("finance/application/src") { include("**/*") },
        fileTree("finance/data/src") { include("**/*") },
        fileTree("transfer/domain/src") { include("**/*") },
        fileTree("transfer/data/src") { include("**/*") },
        fileTree("docs/implementation") { include("*.csv", "*.md") },
        "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
        "gradle/libs.versions.toml",
    )
}

val validateP29ExportFixtures by tasks.registering(Exec::class) {
    group = "verification"
    description = "Proves the P29 gate rejects writer, privacy, paging, SAF, payload and location-opt-in weakening."
    workingDir(layout.projectDirectory)
    commandLine("python3", "-m", "unittest", "scripts.tests.test_p29_export_contracts", "-v")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.files(
        "scripts/validate_p29_export.py",
        "scripts/tests/test_p29_export_contracts.py",
        fileTree("app/src/main/kotlin") { include("**/*.kt") },
        fileTree("core/security/src/main/kotlin") { include("**/*.kt") },
        fileTree("feature/transfer/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/application/src/main/kotlin") { include("**/*.kt") },
        fileTree("finance/data/src/main/kotlin") { include("**/*.kt") },
        fileTree("transfer/domain/src/main/kotlin") { include("**/*.kt") },
        fileTree("transfer/data/src/main/kotlin") { include("**/*.kt") },
    )
}

tasks.register("p29Check") {
    group = "verification"
    description = "Runs P29 static, JVM, lint and architecture evidence; SAF/runtime/UI managed-device suites run separately."
    dependsOn(
        validateP29Export,
        validateP29ExportFixtures,
        "verifyArchitecture",
        "verifySourcePolicies",
        "spotlessCheck",
        "detekt",
        ":transfer:domain:test",
        ":transfer:data:testDebugUnitTest",
        ":finance:application:test",
        ":app:testDebugUnitTest",
        ":app:lintDebug",
        ":core:security:lintDebug",
        ":feature:analysis:lintDebug",
        ":feature:transfer:lintDebug",
        ":finance:data:lintDebug",
        ":transfer:data:lintDebug",
        gradle.includedBuild("build-logic").task(":test"),
    )
}

tasks.register("p29Artifacts") {
    group = "verification"
    description = "Generates P29 coverage and inherited SBOM/license artifacts."
    dependsOn("p02Artifacts")
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
