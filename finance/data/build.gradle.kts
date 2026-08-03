plugins {
    id("ledger.android.library")
    id("ledger.ksp")
}

dependencies {
    api(project(":finance:application"))
    implementation(project(":core:database"))
    implementation(project(":core:security"))
    implementation(project(":core:network"))
    implementation(libs.room.runtime)
    implementation(libs.androidx.sqlite)
}
