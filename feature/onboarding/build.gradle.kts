plugins {
    id("ledger.android.library")
    id("ledger.android.compose")
}

dependencies {
    implementation(project(":finance:application"))
    implementation(project(":analytics:domain"))
    implementation(project(":transfer:domain"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:navigation"))
}
