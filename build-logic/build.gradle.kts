plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

kotlin {
    jvmToolchain(17)
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:${libs.versions.kotlin.get()}")
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:${libs.versions.ksp.get()}")
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "ledger.android.application"
            implementationClass = "app.ledger.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "ledger.android.library"
            implementationClass = "app.ledger.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidTest") {
            id = "ledger.android.test"
            implementationClass = "app.ledger.buildlogic.AndroidTestConventionPlugin"
        }
        register("androidCompose") {
            id = "ledger.android.compose"
            implementationClass = "app.ledger.buildlogic.AndroidComposeConventionPlugin"
        }
        register("kotlinLibrary") {
            id = "ledger.kotlin.library"
            implementationClass = "app.ledger.buildlogic.KotlinLibraryConventionPlugin"
        }
        register("ksp") {
            id = "ledger.ksp"
            implementationClass = "app.ledger.buildlogic.KspConventionPlugin"
        }
        register("architecture") {
            id = "ledger.architecture"
            implementationClass = "app.ledger.buildlogic.ArchitectureConventionPlugin"
        }
    }
}
