plugins {
    id("ledger.architecture")
}

val kspToolingVerification by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    description = "Resolves KSP's lazy compiler artifacts so strict verification metadata is reproducible."
}

dependencies {
    kspToolingVerification(libs.ksp.aa.embeddable)
    kspToolingVerification(libs.ksp.tooling.coroutines)
}

val resolveKspToolingVerification by tasks.registering {
    group = "verification"
    description = "Resolves the exact KSP compiler classpath covered by dependency verification."
    inputs.files(kspToolingVerification)
    doLast {
        logger.lifecycle("KSP tooling verification classpath resolved.")
    }
}

allprojects {
    group = if (path == ":") {
        "app.ledger"
    } else {
        "app.ledger.${path.split(':').first(String::isNotBlank)}"
    }
    version = "0.1.0-p01"

    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("p01Check") {
    group = "verification"
    description = "Runs the complete P01 build and architecture baseline checks."
    dependsOn("verifyArchitecture", "verifyFrozenVersions", resolveKspToolingVerification)
    dependsOn(subprojects.map { it.tasks.matching { task -> task.name == "assemble" } })
}
