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
