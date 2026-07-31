pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ExpenseTracker"

include(
    ":app",
    ":benchmark",
    ":core:common",
    ":core:money",
    ":core:time",
    ":core:designsystem",
    ":core:navigation",
    ":core:database",
    ":core:security",
    ":core:files",
    ":core:network",
    ":core:background",
    ":core:geo",
    ":core:telemetry",
    ":core:testing",
    ":finance:domain",
    ":finance:application",
    ":finance:data",
    ":analytics:domain",
    ":analytics:data",
    ":transfer:domain",
    ":transfer:data",
    ":feature:onboarding",
    ":feature:record",
    ":feature:journal",
    ":feature:accounts",
    ":feature:planning",
    ":feature:liabilities",
    ":feature:settlement",
    ":feature:analysis",
    ":feature:automation",
    ":feature:vault",
    ":feature:transfer",
    ":feature:settings",
    ":widget",
)
