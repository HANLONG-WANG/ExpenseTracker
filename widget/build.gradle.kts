plugins {
    id("ledger.android.library")
    id("ledger.android.compose")
}

dependencies {
    implementation(project(":finance:application"))
    implementation(project(":core:designsystem"))
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
}
