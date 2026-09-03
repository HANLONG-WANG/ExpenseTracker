plugins {
    id("ledger.android.library")
    id("ledger.ksp")
}

dependencies {
    api(project(":analytics:domain"))
    implementation(project(":core:database"))
    implementation(project(":core:security"))
    implementation(libs.room.runtime)
    implementation(libs.androidx.sqlite)
}
