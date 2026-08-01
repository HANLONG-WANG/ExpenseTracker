gradle.beforeProject {
    if (path == ":") {
        val unlocked = configurations.create("auditUnlocked") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }
        dependencies.add(unlocked.name, "org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
        tasks.register("resolveAuditUnlocked") {
            doLast {
                unlocked.resolve()
            }
        }
    }
}
