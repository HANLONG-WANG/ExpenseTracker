plugins {
    id("ledger.kotlin.library")
}

dependencies {
    api(project(":finance:domain"))
    implementation(libs.coroutines.core)
}
