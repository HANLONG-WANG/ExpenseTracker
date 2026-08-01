plugins {
    id("ledger.android.test")
}

android {
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.rules)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.benchmark.macro.junit4)
    implementation(libs.profileinstaller)
}
