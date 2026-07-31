plugins {
    id("ledger.kotlin.library")
}

dependencies {
    api(project(":core:common"))
    api(project(":core:money"))
    api(project(":core:time"))
}
