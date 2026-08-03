plugins {
    id("ledger.android.library")
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)
    testImplementation(libs.mockwebserver)
}
