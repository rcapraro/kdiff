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
    systemProperty("kdiff.version", project.version.toString())

    inputs.files(rootProject.file("README.md"), rootProject.file("CONTRIBUTING.md"))
        .withPropertyName("rootDocumentation")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .optional()
    inputs.dir(rootProject.file("docs"))
        .withPropertyName("docs")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The sources the samples are checked *against* must be inputs too. Without this, editing a file
    // a sample cites leaves this task UP-TO-DATE and `check` passes on a stale result — which is the
    // whole failure the harness exists to prevent.
    //
    // kdiff-sample's own sources are listed with the rest, and are not covered by the module's
    // compile dependency: the samples are compared as *text*, so reflowing a cited line changes what
    // the check reads while leaving the classfile — and so this task's classpath input — identical.
    inputs.files(
        rootProject.fileTree("kdiff-sample/src"),
        rootProject.fileTree("kdiff-tutorial/src"),
        rootProject.fileTree("kdiff-runtime/src/main"),
    )
        .withPropertyName("verifiedSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
