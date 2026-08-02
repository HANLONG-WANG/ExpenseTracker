plugins {
    id("ledger.android.library")
    id("ledger.android.compose")
}

dependencies {
    api(project(":finance:application"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(libs.maplibre)
    implementation(libs.play.services.location)
    implementation(libs.coroutines.core)
    implementation(libs.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)

    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.coroutines.test)
}
