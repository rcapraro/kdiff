// Consumes the *artefacts*, where kdiff-sample consumes the project. A `project(...)` dependency never
// reads a descriptor, so nothing else in this build can tell whether a release resolves.
//
// Not published: absent from the root build's `publishedModules`, so it carries no `explicitApi()`, no
// ABI dump, no Dokka and no publication of its own.

dependencies {
    testImplementation(gradleTestKit())
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

// The consumer build resolves the three coordinates from the repository the publishing tasks write to,
// so those have to have run first. Both the repository's name and its directory come from the root
// build, which declares them, rather than being spelled again here.
val publishedModules = listOf(":kdiff-annotations", ":kdiff-runtime", ":kdiff-processor")
val buildRepositoryName = rootProject.extra["buildRepositoryName"] as String
val buildRepository = rootProject.layout.buildDirectory.dir(rootProject.extra["buildRepositoryPath"] as String)

tasks.test {
    val publishTask = "publishAllPublicationsTo${buildRepositoryName.replaceFirstChar { it.uppercase() }}Repository"
    publishedModules.forEach { dependsOn("$it:$publishTask") }

    // Everything the generated consumer build needs to name. Passed in rather than written into the
    // fixture: a coordinate or a plugin version hardcoded in a test is the same staleness the
    // documentation checks exist to prevent.
    systemProperty("kdiff.group", project.group.toString())
    systemProperty("kdiff.version", project.version.toString())
    systemProperty("kdiff.buildRepo", buildRepository.get().asFile.absolutePath)
    systemProperty("kdiff.kotlinVersion", libs.versions.kotlin.get())
    systemProperty("kdiff.kspVersion", libs.versions.ksp.get())

    // TestKit runs a second Gradle build. Pointing it at this build's own Gradle user home shares the
    // dependency and plugin caches, so the consumer resolves what the outer build already holds
    // instead of populating a cache of its own on every run.
    systemProperty("kdiff.gradleUserHome", gradle.gradleUserHomeDir.absolutePath)

    // The published artefacts are this task's real input: republishing a changed runtime has to re-run
    // the consumer build, and Gradle cannot see that through a `dependsOn` alone.
    //
    // Every `maven-metadata.xml` is excluded because publishing rewrites its `<lastUpdated>` on every
    // run — the publish task is untracked, so it always executes — and an input that changes every time
    // means this task is never up to date. Six TestKit builds on a no-op `check` is not the cost this
    // gate is meant to have, and the metadata says nothing about the artefacts a consumer resolves.
    inputs.files(buildRepository.map { it.asFileTree.matching { exclude("**/maven-metadata.xml*") } })
        .withPropertyName("publishedArtifacts")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // A second Gradle invocation per test class is slow enough that running them in parallel would
    // contend for the same caches rather than finish sooner.
    maxParallelForks = 1
}
