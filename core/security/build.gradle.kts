plugins {
    id("ledger.android.library")
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(libs.room.runtime)
    implementation(libs.androidx.sqlite)
    implementation(libs.coroutines.core)
    implementation(libs.tink)
    implementation(libs.bouncycastle.provider)
    implementation(libs.androidx.biometric)

    androidTestImplementation(libs.coroutines.test)
}
