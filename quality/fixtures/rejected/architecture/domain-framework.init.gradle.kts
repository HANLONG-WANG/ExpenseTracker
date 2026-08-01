gradle.beforeProject {
    if (path == ":finance:domain") {
        pluginManager.withPlugin("java-library") {
            dependencies.add("implementation", "com.squareup.okhttp3:okhttp:5.1.0")
        }
    }
}
