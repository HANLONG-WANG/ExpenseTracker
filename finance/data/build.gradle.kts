plugins {
    id("ledger.android.library")
    id("ledger.ksp")
}

dependencies {
    api(project(":finance:application"))
    implementation(project(":core:database"))
}
