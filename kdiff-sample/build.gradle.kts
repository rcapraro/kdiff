plugins {
    alias(libs.plugins.ksp)
}

dependencies {
    implementation(project(":kdiff-annotations"))
    implementation(project(":kdiff-runtime"))
    ksp(project(":kdiff-processor"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

// DocumentationSamplesSpec reads README.md and docs/ from the repository root, which is not the
// test's working directory. An absolute path from Gradle also survives being run from an IDE.
//
// The documentation has to be declared an input, or Gradle sees no reason to re-run the tests when a
// sample is edited and `check` passes on a stale result — which is exactly when the check matters.
tasks.test {
    systemProperty("kdiff.repoRoot", rootProject.projectDir.absolutePath)

    inputs.files(rootProject.file("README.md"), rootProject.file("CONTRIBUTING.md"))
        .withPropertyName("rootDocumentation")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .optional()
    inputs.dir(rootProject.file("docs"))
        .withPropertyName("docs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
