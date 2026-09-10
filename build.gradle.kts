plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.maven.publish.base) apply false
}

val publishedModules = setOf("kdiff-annotations", "kdiff-runtime", "kdiff-processor")

// Named once and shared, because kdiff-integration reads both: the publication task it depends on is
// derived from the repository name, and its consumer build resolves from the directory. Held in
// `extra` rather than in a local, which only this script could see — a mismatch between the two would
// surface as a consumer build that resolves nothing, with no error naming the cause.
val BUILD_REPOSITORY = "buildRepo"
val BUILD_REPOSITORY_PATH = "repo"

extra["buildRepositoryName"] = BUILD_REPOSITORY
extra["buildRepositoryPath"] = BUILD_REPOSITORY_PATH

// Read here rather than inside `subprojects`: the type-safe catalog accessors exist only in the
// root project's own script scope.
val ktlintToolVersion = libs.versions.ktlintTool.get()

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "dev.detekt")

    group = "io.github.rcapraro"
    version = "1.0.0"

    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(ktlintToolVersion)
        // KotlinPoet's output is not a contributor's to format, and holding it to a
        // contributor's rules would fail a gate nobody can act on.
        filter { exclude { it.file.path.contains("${File.separator}generated${File.separator}") } }
    }

    // ktlint's own failure names the report file but not the command that fixes it, and the ABI gate
    // does name `updateKotlinAbi`. A finalizer runs even when the task it follows fails, and reading
    // the report tells it whether there was anything to fix — so the hint appears exactly when it is
    // useful and never on a clean run.
    val fixCommand = "${project.path}:ktlintFormat".removePrefix(":")
    val ktlintReports = layout.buildDirectory.dir("reports/ktlint")
    val ktlintFixHint = tasks.register("ktlintFixHint") {
        val reports = ktlintReports
        doLast {
            // Only the *check* reports. The format tasks write their own alongside them, and a stale
            // one left by an earlier `ktlintFormat` would otherwise make the next clean check claim
            // there was something to fix.
            val found = reports.get().asFile.walkTopDown().any {
                it.isFile && it.extension == "txt" && it.parentFile.name.endsWith("Check") && it.length() > 0
            }
            if (found) logger.lifecycle("ktlint: run `./gradlew $fixCommand` to fix what it reported.")
        }
    }
    tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.GenerateReportsTask>().configureEach {
        // Checks only: finalizing the format tasks too would print "run ktlintFormat" immediately
        // after ktlintFormat had already fixed everything it reported.
        if (name.endsWith("Check")) finalizedBy(ktlintFixHint)
    }

    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        // So the checked-in config states only the project's deviations, and stays short enough that
        // a reviewer can read every rule it turns off.
        buildUponDefaultConfig.set(true)
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    }

    // The plugin wires only the plain `detekt` task into `check`, and that one runs without a compile
    // classpath — so every rule needing type resolution is silently skipped. Wire the per-source-set
    // tasks that do have one (`detektMain`, `detektTest`, `detektJmh`; the `…SourceSet` variants are
    // the classpath-free ones) and disable the plain task so nothing is analysed twice.
    val typeResolvingDetekt = tasks.withType<dev.detekt.gradle.Detekt>()
        .matching { it.name != "detekt" && !it.name.endsWith("SourceSet") }

    tasks.named("detekt") { enabled = false }
    tasks.named("check") { dependsOn(typeResolvingDetekt) }

    tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
        // Same reason ktlint skips them: KotlinPoet's output is not a contributor's to fix. Matched on
        // the absolute path rather than by an Ant pattern, which detekt resolves against each source
        // root — and the generated roots are source roots themselves, so no pattern can name them.
        exclude { it.file.absolutePath.contains("${File.separator}build${File.separator}generated${File.separator}") }
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
        if (project.name in publishedModules) {
            explicitApi()

            // The published API is a surface consumers compile against, so removing from it must fail
            // the build rather than reach a consumer. Recorded in `api/`, updated with `updateKotlinAbi`.
            @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
            abiValidation {
                // The check task is not wired into `check` by the plugin, so wire it here rather than
                // naming the task by string.
                tasks.named("check") { dependsOn(checkTaskProvider) }
            }
        }
    }

    if (project.name in publishedModules) {
        // Not wired into `check` as a gate — reference documentation is an artefact to publish, not
        // something to pass. Run `./gradlew dokkaGenerate` to read it.
        //
        // It does nonetheless run during `check`, and that is worth knowing rather than discovering:
        // kdiff-integration's consumer build resolves the three modules from `build/repo`, publishing
        // there runs the real publication, and the real publication carries a Dokka javadoc jar. About
        // 4s of a ~52s check. Testing what a release actually produces is what buys that, and a
        // publication that skipped the javadoc jar locally would no longer be that release.
        apply(plugin = "org.jetbrains.dokka")

        apply(plugin = "com.vanniktech.maven.publish.base")

        // A repository inside the build, for the consumer build in kdiff-integration to resolve from.
        // `publishToMavenLocal` would do the same job — it is what a maintainer runs to inspect a
        // release — but making `check` write to `~/.m2` would grow every contributor's local
        // repository on every build, and a stale entry left there would shadow the next build's output.
        extensions.configure<PublishingExtension> {
            repositories.maven {
                name = BUILD_REPOSITORY
                url = rootProject.layout.buildDirectory.dir(BUILD_REPOSITORY_PATH).get().asFile.toURI()
            }
        }

        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            // Stated rather than left to the plugin's platform detection, which is `@Incubating`, and
            // so the javadoc jar is visibly the Dokka HTML this module already knows how to build.
            configure(
                com.vanniktech.maven.publish.KotlinJvm(
                    javadocJar = com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
                ),
            )

            publishToMavenCentral()

            // Central requires signatures, and only the release workflow holds the key: a local
            // `publishToMavenLocal` has no signatory, and demanding one would make the artifacts of a
            // release impossible to inspect before tagging it.
            if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()

            pom {
                name.set(project.name)
                description.set(
                    "Annotation-driven structural diff, patch and change tracking for Kotlin",
                )
                url.set("https://github.com/rcapraro/kdiff")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://github.com/rcapraro/kdiff/blob/main/LICENSE")
                    }
                }
                developers {
                    developer {
                        id.set("rcapraro")
                        url.set("https://github.com/rcapraro")
                    }
                }
                scm {
                    url.set("https://github.com/rcapraro/kdiff")
                    connection.set("scm:git:https://github.com/rcapraro/kdiff.git")
                    developerConnection.set("scm:git:ssh://git@github.com/rcapraro/kdiff.git")
                }
            }
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.allWarningsAsErrors.set(true)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
