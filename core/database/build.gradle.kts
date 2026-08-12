plugins {
    id("ledger.android.library")
    id("ledger.ksp")
}

dependencies {
    api(libs.room.runtime)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)
    ksp(libs.room.compiler)

    androidTestImplementation(libs.room.testing)
}

ksp {
    arg("room.schemaLocation", project.file("schemas").absolutePath)
    arg("room.generateKotlin", "true")
}
