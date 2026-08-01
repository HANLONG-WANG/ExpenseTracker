plugins {
    id("ledger.android.library")
    id("ledger.android.compose")
}

dependencies {
    api(project(":core:money"))
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.vico.compose.material3)
    implementation(libs.jankstats)
    debugCompileOnly(libs.compose.ui.tooling.preview)
    debugCompileOnly(libs.compose.ui.tooling)
}
