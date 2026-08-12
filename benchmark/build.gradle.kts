plugins {
    id("ledger.android.test")
}

android {
    experimentalProperties["android.experimental.self-instrumenting"] = true
    defaultConfig {
        // P35 is explicitly authorized to use API 28/API 36 emulators. Keep AndroidX's
        // environment finding visible as a warning while allowing reproducible collection.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
}

dependencies {
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.rules)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.benchmark.macro.junit4)
    implementation(libs.profileinstaller)
    implementation(libs.androidx.test.uiautomator)
}
