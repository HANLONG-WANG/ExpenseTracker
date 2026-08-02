plugins {
    id("ledger.android.library")
    id("ledger.android.compose")
}

dependencies {
    api(project(":finance:application"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:security"))
    implementation(libs.androidx.sqlite)
    implementation(libs.room.runtime)
    implementation(libs.tink)
    implementation(libs.coroutines.core)
    implementation(libs.coil.core)
    implementation(libs.coil.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)

    androidTestImplementation(libs.coroutines.test)
}
