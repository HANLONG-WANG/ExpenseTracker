plugins {
    id("ledger.android.library")
    id("ledger.android.compose")
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.navigation3.runtime)
    api(libs.navigation3.ui)
}
