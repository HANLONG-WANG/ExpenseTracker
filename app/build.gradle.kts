plugins {
    id("ledger.android.application")
    id("ledger.android.compose")
    id("ledger.ksp")
    alias(libs.plugins.hilt)
    alias(libs.plugins.protobuf)
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:navigation"))
    implementation(project(":core:security"))
    implementation(project(":core:time"))
    implementation(project(":core:background"))
    implementation(project(":core:geo"))
    implementation(project(":core:files"))
    implementation(project(":finance:application"))
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
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.datastore)
    implementation(libs.protobuf.java)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.paging.common)
    implementation(libs.paging.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.sqlite)
    implementation(libs.okhttp)
    implementation(libs.play.services.auth)
    implementation(libs.work.runtime.ktx)
}

hilt {
    enableAggregatingTask = false
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java")
            }
            doLast {
                val javaDoc = Regex("/\\*\\*[\\s\\S]*?\\*/")
                outputs.files.asFileTree.matching { include("**/*.java") }.files.forEach { generatedJava ->
                    val source = generatedJava.readText()
                    val lintCompatible = source.replace(javaDoc, "")
                    if (source != lintCompatible) generatedJava.writeText(lintCompatible)
                }
            }
        }
    }
}
