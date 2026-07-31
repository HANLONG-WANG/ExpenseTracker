plugins {
    id("ledger.android.application")
    id("ledger.android.compose")
}

dependencies {
    implementation(project(":finance:data"))
    implementation(project(":analytics:data"))
    implementation(project(":transfer:data"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:record"))
    implementation(project(":feature:journal"))
    implementation(project(":feature:accounts"))
    implementation(project(":feature:planning"))
    implementation(project(":feature:liabilities"))
    implementation(project(":feature:settlement"))
    implementation(project(":feature:analysis"))
    implementation(project(":feature:automation"))
    implementation(project(":feature:vault"))
    implementation(project(":feature:transfer"))
    implementation(project(":feature:settings"))
    implementation(project(":widget"))
}
