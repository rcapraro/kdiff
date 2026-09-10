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
    systemProperty("kdiff.group", project.group.toString())
    systemProperty("kdiff.version", project.version.toString())

    // The install snippet tells a reader which Kotlin and KSP plugin versions to apply, in the same
    // fenced block whose coordinates are already pinned. They are the same kind of thing a reader
    // copies and the same kind of thing that goes stale, so they are checked the same way.
    systemProperty("kdiff.kotlinVersion", libs.versions.kotlin.get())
    systemProperty("kdiff.kspVersion", libs.versions.ksp.get())

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
    // kdiff-processor/src/main is here for DocumentationMessagesSpec, which reads the diagnostics
    // errors.md quotes out of it as text. Without it, rewording a diagnostic leaves this task
    // UP-TO-DATE and the page unchecked — the same staleness the sources below are declared against.
    inputs.files(
        rootProject.fileTree("kdiff-sample/src"),
        rootProject.fileTree("kdiff-tutorial/src"),
        rootProject.fileTree("kdiff-runtime/src/main"),
        rootProject.fileTree("kdiff-processor/src/main"),
    )
        .withPropertyName("verifiedSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
