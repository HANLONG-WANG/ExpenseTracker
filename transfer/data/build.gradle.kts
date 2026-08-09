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
    implementation(libs.commons.csv)
    implementation(libs.fastexcel)
    implementation(libs.fastexcel.reader)
    implementation(libs.icu4j)
    implementation(libs.stax.api)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.documentfile)
    implementation(libs.coroutines.core)

    testImplementation(libs.fastexcel)
    androidTestImplementation(libs.fastexcel)
}

// The 100k-row streaming fixtures must pass within a deliberately constrained JVM heap.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    maxHeapSize = "256m"
}
