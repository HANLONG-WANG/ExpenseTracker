package app.ledger.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.TestExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.io.File

private const val COMPILE_SDK = 36
private const val MIN_SDK = 28
private const val TARGET_SDK = 36
private const val JDK_VERSION = 17

private fun Project.androidNamespace(): String = "app.ledger" + path.split(':').filter(String::isNotBlank).joinToString("") { segment ->
    "." + segment.replace("-", "")
}

private fun Project.configureKotlin17() {
    tasks.withType(KotlinJvmCompile::class.java).configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        compilerOptions.allWarningsAsErrors.set(true)
    }
}

private fun Project.catalogLibrary(alias: String) = extensions.getByType(VersionCatalogsExtension::class.java).named("libs").findLibrary(alias).get()

private fun Project.configureJvmTestStack() {
    dependencies.add("testImplementation", dependencies.platform(catalogLibrary("junit-bom")))
    dependencies.add("testImplementation", catalogLibrary("junit-jupiter"))
    dependencies.add("testImplementation", catalogLibrary("kotest-assertions"))
    dependencies.add("testImplementation", catalogLibrary("kotest-property"))
    dependencies.add("testImplementation", catalogLibrary("mockk"))
    dependencies.add("testImplementation", catalogLibrary("coroutines-test"))
    dependencies.add("testImplementation", catalogLibrary("turbine"))
    dependencies.add("testRuntimeOnly", catalogLibrary("junit-platform-launcher"))
    tasks.withType(Test::class.java).configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
        }
    }
}

private fun Project.configureAndroidTestStack() {
    dependencies.add("androidTestImplementation", catalogLibrary("androidx-test-core"))
    dependencies.add("androidTestImplementation", catalogLibrary("androidx-test-runner"))
    dependencies.add("androidTestImplementation", catalogLibrary("androidx-test-rules"))
    dependencies.add("androidTestImplementation", catalogLibrary("androidx-test-ext-junit"))
    dependencies.add("androidTestImplementation", catalogLibrary("espresso-core"))
}

private fun ApplicationExtension.configureManagedDevices() {
    testOptions.animationsDisabled = true
    testOptions.managedDevices.localDevices.create("pixel2Api28") {
        device = "Pixel 2"
        apiLevel = MIN_SDK
        systemImageSource = "google"
        testedAbi = "x86"
    }
    testOptions.managedDevices.localDevices.create("pixel6Api36") {
        device = "Pixel 6"
        apiLevel = TARGET_SDK
        systemImageSource = "google"
        testedAbi = "x86_64"
    }
}

private fun LibraryExtension.configureLibraryManagedDevices(includeMinSdk: Boolean) {
    testOptions.animationsDisabled = true
    if (includeMinSdk) {
        testOptions.managedDevices.localDevices.create("pixel2Api28") {
            device = "Pixel 2"
            apiLevel = MIN_SDK
            systemImageSource = "google"
            testedAbi = "x86"
        }
    }
    testOptions.managedDevices.localDevices.create("pixel6Api36") {
        device = "Pixel 6"
        apiLevel = TARGET_SDK
        systemImageSource = "google"
        testedAbi = "x86_64"
    }
}

private fun TestExtension.configureManagedDevices() {
    testOptions.managedDevices.localDevices.create("pixel6Api36") {
        device = "Pixel 6"
        apiLevel = TARGET_SDK
        systemImageSource = "google"
        testedAbi = "x86_64"
    }
}

private fun ApplicationExtension.configureAndroidApplication(project: Project) {
    namespace = project.androidNamespace()
    compileSdk = COMPILE_SDK
    defaultConfig {
        applicationId = "app.ledger.expensetracker"
        minSdk = MIN_SDK
        targetSdk = TARGET_SDK
        versionCode = 1
        versionName = "0.2.0-p02"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    configureManagedDevices()
}

private fun LibraryExtension.configureAndroidLibrary(project: Project) {
    namespace = project.androidNamespace()
    compileSdk = COMPILE_SDK
    defaultConfig {
        minSdk = MIN_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if (project.path in DEVICE_TEST_LIBRARY_PATHS) {
        configureLibraryManagedDevices(includeMinSdk = false)
    }
    if (project.path == ":core:designsystem") {
        configureLibraryManagedDevices(includeMinSdk = true)
    }
}

private val DEVICE_TEST_LIBRARY_PATHS = setOf(
    ":core:database",
    ":core:security",
    ":core:files",
    ":core:geo",
    ":finance:data",
    ":feature:record",
    ":feature:journal",
    ":feature:planning",
    ":feature:liabilities",
    ":feature:settlement",
    ":feature:settings",
    ":feature:onboarding",
)

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val owner = this
        pluginManager.apply("com.android.application")
        extensions.configure(ApplicationExtension::class.java) {
            configureAndroidApplication(owner)
        }
        configureKotlin17()
        configureJvmTestStack()
        configureAndroidTestStack()
    }
}

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val owner = this
        pluginManager.apply("com.android.library")
        extensions.configure(LibraryExtension::class.java) {
            configureAndroidLibrary(owner)
        }
        configureKotlin17()
        configureJvmTestStack()
        configureAndroidTestStack()
    }
}

class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.test")
        extensions.configure(TestExtension::class.java) {
            namespace = androidNamespace()
            compileSdk = COMPILE_SDK
            targetProjectPath = ":app"
            defaultConfig {
                minSdk = MIN_SDK
                targetSdk = TARGET_SDK
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            configureManagedDevices()
        }
        configureKotlin17()
    }
}

class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.withPlugin("com.android.application") {
            extensions.configure(ApplicationExtension::class.java) {
                buildFeatures.compose = true
            }
        }
        pluginManager.withPlugin("com.android.library") {
            extensions.configure(LibraryExtension::class.java) {
                buildFeatures.compose = true
            }
        }
        dependencies.add("androidTestImplementation", dependencies.platform(catalogLibrary("compose-bom")))
        dependencies.add("androidTestImplementation", catalogLibrary("compose-ui-test-junit4"))
        dependencies.add("androidTestImplementation", catalogLibrary("compose-foundation"))
        dependencies.add("debugImplementation", catalogLibrary("compose-ui-test-manifest"))
        Unit
    }
}

class KotlinLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("java-library")
        extensions.configure(JavaPluginExtension::class.java) {
            toolchain.languageVersion.set(JavaLanguageVersion.of(JDK_VERSION))
        }
        extensions.configure(KotlinJvmProjectExtension::class.java) {
            jvmToolchain(JDK_VERSION)
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            compilerOptions.allWarningsAsErrors.set(true)
        }
        configureJvmTestStack()
    }
}

class KspConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("com.google.devtools.ksp")
    }
}

class ArchitectureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        require(target == target.rootProject) { "ledger.architecture must be applied to the root project" }
        val architectureTask = target.tasks.register("verifyArchitecture", VerifyArchitectureTask::class.java) {
            group = "verification"
            description = "Verifies the frozen module set, plugin boundaries, and every direct project edge."
        }
        target.tasks.register("verifyFrozenVersions", VerifyFrozenVersionsTask::class.java) {
            group = "verification"
            description = "Verifies JDK, Gradle, Android SDK, wrapper, and stable version-catalog pins."
            rootDirectory.set(target.layout.projectDirectory)
            runningGradleVersion.set(GradleVersion.current().version)
            runningJavaVersion.set(JavaVersion.current().toString())
        }
        target.tasks.register("verifySourcePolicies", VerifySourcePoliciesTask::class.java) {
            group = "verification"
            description = "Rejects privacy, UI-governance and financial-write boundary violations in production sources."
            rootDirectory.set(target.layout.projectDirectory)
            screenContract.set(
                target.layout.projectDirectory.file(
                    "docs/UI设计稿与实现契约_v1.0/android_ledger_screen_contract_v1.yaml",
                ),
            )
            sourceFiles.from(
                target.fileTree(target.layout.projectDirectory) {
                    include("app/src/main/**/*.kt", "app/src/main/**/*.java")
                    include("core/**/src/main/**/*.kt", "core/**/src/main/**/*.java")
                    include("finance/**/src/main/**/*.kt", "finance/**/src/main/**/*.java")
                    include("analytics/**/src/main/**/*.kt", "analytics/**/src/main/**/*.java")
                    include("transfer/**/src/main/**/*.kt", "transfer/**/src/main/**/*.java")
                    include("feature/**/src/main/**/*.kt", "feature/**/src/main/**/*.java")
                    include("widget/src/main/**/*.kt", "widget/src/main/**/*.java")
                    exclude("**/build/**")
                },
            )
            target.providers.gradleProperty("qualityPolicyFixture").orNull?.let { fixturePath ->
                fixtureDirectory.set(target.layout.projectDirectory.dir(fixturePath))
                sourceFiles.from(target.fileTree(fixturePath) { include("**/*.kt", "**/*.java") })
            }
        }
        target.gradle.projectsEvaluated {
            architectureTask.configure {
                actualProjectPaths.set(target.subprojects.map { candidate -> candidate.path }.sorted())
                directEdges.set(
                    target.subprojects.associate { candidate ->
                        val edges = candidate.configurations
                            .flatMap { configuration -> configuration.dependencies.withType(ProjectDependency::class.java) }
                            .map { dependency -> dependency.path }
                            .filter { dependencyPath -> dependencyPath != candidate.path }
                            .toSortedSet()
                        candidate.path to edges.joinToString(",")
                    },
                )
                externalDependencies.set(
                    target.subprojects.associate { candidate ->
                        val dependencies = candidate.configurations
                            .flatMap { configuration ->
                                configuration.dependencies
                                    .filterNot { dependency -> dependency is ProjectDependency }
                                    .mapNotNull { dependency ->
                                        dependency.group?.let { group ->
                                            "${configuration.name}|$group:${dependency.name}"
                                        }
                                    }
                            }
                            .toSortedSet()
                        candidate.path to dependencies.joinToString(";")
                    },
                )
                kotlinJvmProjects.set(
                    target.subprojects.filter { candidate ->
                        candidate.pluginManager.hasPlugin("org.jetbrains.kotlin.jvm")
                    }.map { candidate -> candidate.path }.sorted(),
                )
                androidProjects.set(
                    target.subprojects.filter { candidate ->
                        candidate.pluginManager.hasPlugin("com.android.library") ||
                            candidate.pluginManager.hasPlugin("com.android.application") ||
                            candidate.pluginManager.hasPlugin("com.android.test")
                    }.map { candidate -> candidate.path }.sorted(),
                )
                forbiddenPluginProjects.set(
                    target.subprojects.filter { candidate ->
                        candidate.pluginManager.hasPlugin("org.jetbrains.kotlin.android") ||
                            candidate.pluginManager.hasPlugin("org.jetbrains.kotlin.kapt") ||
                            candidate.pluginManager.hasPlugin("kotlin-kapt")
                    }.map { candidate -> candidate.path }.sorted(),
                )
            }
        }
    }
}

abstract class VerifyArchitectureTask : DefaultTask() {
    @get:Input
    abstract val actualProjectPaths: ListProperty<String>

    @get:Input
    abstract val directEdges: MapProperty<String, String>

    @get:Input
    abstract val externalDependencies: MapProperty<String, String>

    @get:Input
    abstract val kotlinJvmProjects: ListProperty<String>

    @get:Input
    abstract val androidProjects: ListProperty<String>

    @get:Input
    abstract val forbiddenPluginProjects: ListProperty<String>

    @Suppress("NestedBlockDepth")
    @TaskAction
    fun verify() {
        val expectedProjects = approvedEdges.keys
        val actualProjects = actualProjectPaths.get().toSet()
        val errors = mutableListOf<String>()

        if (actualProjects != expectedProjects) {
            errors += "Module set differs. Missing=${expectedProjects - actualProjects}; extra=${actualProjects - expectedProjects}"
        }

        directEdges.get().forEach { (candidatePath, encodedEdges) ->
            val directProjectEdges = encodedEdges.split(',').filter(String::isNotEmpty).toSet()
            val approved = approvedEdges[candidatePath].orEmpty()
            if (directProjectEdges != approved) {
                errors += "$candidatePath edges differ. Expected=$approved; actual=$directProjectEdges"
            }

            if (candidatePath.startsWith(":feature:")) {
                directProjectEdges.filter { edge -> edge.startsWith(":feature:") || edge.endsWith(":data") }
                    .forEach { edge -> errors += "Feature boundary violation: $candidatePath -> $edge" }
            }

            if (candidatePath in pureKotlinProjects) {
                if (candidatePath !in kotlinJvmProjects.get()) {
                    errors += "Pure Kotlin module lacks Kotlin/JVM plugin: $candidatePath"
                }
                if (candidatePath in androidProjects.get()) {
                    errors += "Pure Kotlin module applies an Android plugin: $candidatePath"
                }
                externalDependencies.get()[candidatePath]
                    .orEmpty()
                    .split(';')
                    .filter(String::isNotEmpty)
                    .forEach { encodedDependency ->
                        val coordinate = encodedDependency.substringAfter('|')
                        if (forbiddenDomainFrameworkPrefixes.any(coordinate::startsWith)) {
                            errors += "[ARCH-DOMAIN-FRAMEWORK] $candidatePath declares forbidden external dependency " +
                                encodedDependency.replace('|', ':')
                        }
                    }
            }
        }
        forbiddenPluginProjects.get().forEach { candidatePath ->
            errors += "Forbidden Kotlin Android/kapt plugin in $candidatePath"
        }

        if (errors.isNotEmpty()) {
            throw GradleException(errors.joinToString(separator = "\n"))
        }
        logger.lifecycle("Architecture verification passed: ${actualProjects.size} root projects and all direct edges match the frozen graph.")
    }

    private companion object {
        val featureProjects = setOf(
            ":feature:onboarding",
            ":feature:record",
            ":feature:journal",
            ":feature:accounts",
            ":feature:planning",
            ":feature:liabilities",
            ":feature:settlement",
            ":feature:analysis",
            ":feature:automation",
            ":feature:vault",
            ":feature:transfer",
            ":feature:settings",
        )
        val featureEdges = setOf(
            ":finance:application",
            ":analytics:domain",
            ":transfer:domain",
            ":core:designsystem",
            ":core:navigation",
        )
        val pureKotlinProjects = setOf(
            ":core:common",
            ":core:money",
            ":core:time",
            ":core:testing",
            ":finance:domain",
            ":finance:application",
            ":analytics:domain",
            ":transfer:domain",
        )
        val forbiddenDomainFrameworkPrefixes = setOf(
            "android:",
            "androidx.",
            "com.android.",
            "com.google.dagger:",
            "com.squareup.okhttp3:",
            "com.squareup.retrofit2:",
            "io.reactivex",
        )
        val approvedEdges = buildMap<String, Set<String>> {
            put(":analytics", emptySet())
            put(":core", emptySet())
            put(":feature", emptySet())
            put(":finance", emptySet())
            put(":transfer", emptySet())
            put(
                ":app",
                featureProjects + setOf(
                    ":core:common",
                    ":core:designsystem",
                    ":core:navigation",
                    ":core:security",
                    ":core:time",
                    ":core:background",
                    ":core:files",
                    ":core:geo",
                    ":finance:application",
                    ":finance:data",
                    ":analytics:data",
                    ":transfer:data",
                    ":widget",
                ),
            )
            put(":benchmark", setOf(":app"))
            put(":core:common", emptySet())
            put(":core:money", setOf(":core:common"))
            put(":core:time", setOf(":core:common"))
            put(":core:designsystem", setOf(":core:money"))
            put(":core:navigation", setOf(":core:common"))
            put(":core:database", emptySet())
            put(":core:security", setOf(":core:common", ":core:database"))
            put(
                ":core:files",
                setOf(":core:common", ":core:database", ":core:designsystem", ":core:security", ":finance:application"),
            )
            put(":core:network", emptySet())
            put(":core:background", emptySet())
            put(":core:geo", setOf(":core:common", ":core:designsystem", ":finance:application"))
            put(":core:telemetry", emptySet())
            put(":core:testing", emptySet())
            put(":finance:domain", setOf(":core:common", ":core:money", ":core:time"))
            put(":finance:application", setOf(":finance:domain"))
            put(":finance:data", setOf(":finance:application", ":core:database", ":core:network", ":core:security"))
            put(":analytics:domain", setOf(":finance:domain"))
            put(":analytics:data", setOf(":analytics:domain", ":core:database"))
            put(":transfer:domain", setOf(":finance:application"))
            put(":transfer:data", setOf(":transfer:domain", ":core:background", ":core:files", ":core:network", ":core:security"))
            featureProjects.forEach { feature -> put(feature, featureEdges) }
            put(":widget", setOf(":finance:application", ":core:designsystem"))
        }
    }
}

abstract class VerifyFrozenVersionsTask : DefaultTask() {
    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @get:Input
    abstract val runningGradleVersion: Property<String>

    @get:Input
    abstract val runningJavaVersion: Property<String>

    @TaskAction
    fun verify() {
        val errors = mutableListOf<String>()
        val root = rootDirectory.get().asFile
        val catalog = File(root, "gradle/libs.versions.toml").readText()
        val wrapper = File(root, "gradle/wrapper/gradle-wrapper.properties")

        if (runningJavaVersion.get() != JavaVersion.VERSION_17.toString()) {
            errors += "JDK 17 required; current=${runningJavaVersion.get()}"
        }
        if (runningGradleVersion.get() != "9.5.1") {
            errors += "Gradle 9.5.1 required; current=${runningGradleVersion.get()}"
        }
        frozenVersions.forEach { (name, value) ->
            if (!Regex("(?m)^${Regex.escape(name)}\\s*=\\s*\"${Regex.escape(value)}\"$").containsMatchIn(catalog)) {
                errors += "Missing frozen version pin $name=$value"
            }
        }
        catalog.lineSequence()
            .filter { line -> line.contains("version") || Regex("=\\s*\"").containsMatchIn(line) }
            .filter { line -> Regex("(?i)(alpha|beta|rc|snapshot|next|latest|release|\\+)").containsMatchIn(line) }
            .forEach { line -> errors += "Dynamic or prerelease catalog value: ${line.trim()}" }
        if (!catalog.contains("ksp = { id = \"com.google.devtools.ksp\", version.ref = \"ksp\" }")) {
            errors += "KSP plugin is not registered in the version catalog"
        }
        if (!wrapper.isFile) {
            errors += "Gradle wrapper properties are missing"
        } else {
            val wrapperText = wrapper.readText()
            if (!wrapperText.contains("gradle-9.5.1-bin.zip")) errors += "Wrapper is not pinned to Gradle 9.5.1"
            if (!wrapperText.contains("bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f")) {
                errors += "Wrapper distribution SHA-256 is missing or incorrect"
            }
        }

        val forbiddenPatterns = mapOf(
            "kotlin-android" to Regex("kotlin-android|org\\.jetbrains\\.kotlin\\.android"),
            "kapt" to Regex("(?i)\\bkapt\\b|kotlin-kapt"),
            "Room 3" to Regex("androidx\\.room[^\n]*[=:]\\s*\"?3\\."),
            "Retrofit" to Regex("(?i)retrofit"),
            "RxJava" to Regex("(?i)rxjava"),
        )
        root.walkTopDown()
            .filter { file -> file.isFile && file.extension in setOf("kts", "toml") && "build/" !in file.invariantSeparatorsPath }
            .forEach { file ->
                val text = file.readText()
                forbiddenPatterns.forEach { (label, pattern) ->
                    if (pattern.containsMatchIn(text) && file.name != "ConventionPlugins.kt") {
                        errors += "Forbidden $label reference in ${file.relativeTo(root)}"
                    }
                }
            }

        if (errors.isNotEmpty()) throw GradleException(errors.joinToString(separator = "\n"))
        logger.lifecycle("Frozen version verification passed: JDK 17, Gradle 9.5.1, API 28/36, AGP 9.3.1, Kotlin 2.4.10, KSP 2.3.10.")
    }

    private companion object {
        val frozenVersions = mapOf(
            "agp" to "9.3.1",
            "kotlin" to "2.4.10",
            "ksp" to "2.3.10",
            "room" to "2.8.4",
            "sqlcipher" to "4.17.0",
            "maplibre" to "13.4.1",
            "coil" to "3.5.0",
            "tink" to "1.23.0",
            "fastexcel" to "0.20.2",
        )
    }
}
