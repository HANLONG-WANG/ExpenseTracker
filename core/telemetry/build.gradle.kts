plugins {
    id("ledger.android.library")
}

dependencies {
    implementation(libs.acra.core)
    implementation(libs.okhttp)
    testImplementation(libs.mockwebserver)
}
