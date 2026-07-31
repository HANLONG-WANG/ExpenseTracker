plugins {
    id("ledger.android.library")
    id("ledger.ksp")
}

dependencies {
    api(project(":analytics:domain"))
    implementation(project(":core:database"))
}
