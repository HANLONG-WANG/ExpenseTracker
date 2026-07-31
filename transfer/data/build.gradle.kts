plugins {
    id("ledger.android.library")
    id("ledger.ksp")
}

dependencies {
    api(project(":transfer:domain"))
    implementation(project(":core:background"))
    implementation(project(":core:files"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
}
