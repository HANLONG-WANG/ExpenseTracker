plugins {
    id("ledger.android.library")
    id("ledger.ksp")
}

dependencies {
    api(project(":finance:application"))
    implementation(project(":core:database"))
    implementation(libs.room.runtime)
    implementation(libs.androidx.sqlite)
}
