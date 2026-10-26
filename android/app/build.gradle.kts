plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
    alias(libs.plugins.serialization)
    alias(libs.plugins.ksp)
}
// Keep the release identity outside the checkout. Debug builds need no release secrets.
val releaseSigningDirectory = providers.environmentVariable("YOHAKU_SIGNING_DIR")
    .orElse("${System.getProperty("user.home")}/.local/share/yohaku/signing")
val releaseSigningPassword = providers.provider {
    file("${releaseSigningDirectory.get()}/store-password")
        .takeIf { it.isFile }?.readText()?.trimEnd()
}
android {
    namespace = "com.yohaku.expensetracker"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.yohaku.expensetracker"
        minSdk = 29
        targetSdk = 37
        versionCode = providers.gradleProperty("yohakuVersionCode").orElse("5").get().toInt()
        versionName = providers.gradleProperty("yohakuVersionName").orElse("0.5.0").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("release") {
            storeFile = file("${releaseSigningDirectory.get()}/release.p12")
            storeType = "PKCS12"
            storePassword = releaseSigningPassword.orNull
            keyAlias = "yohaku-release"
            keyPassword = releaseSigningPassword.orNull
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".dev" }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation(libs.activity)
    implementation(libs.viewmodel)
    implementation(libs.lifecycle)
    implementation(libs.room)
    ksp(libs.room.compiler)
    implementation(libs.datastore)
    implementation(libs.work)
    implementation(libs.serialization)
    implementation("androidx.core:core-ktx:1.18.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
