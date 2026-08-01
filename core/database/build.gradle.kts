plugins {
    id("ledger.android.library")
    id("ledger.ksp")
}

dependencies {
    androidTestImplementation(libs.room.testing)
}
