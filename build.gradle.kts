plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.dokka) apply false
}

val publishedModules = setOf("kdiff-annotations", "kdiff-runtime", "kdiff-processor")

// Read here rather than inside `subprojects`: the type-safe catalog accessors exist only in the
// root project's own script scope.
val ktlintToolVersion = libs.versions.ktlintTool.get()

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "dev.detekt")

    group = "io.github.kdiff"
    version = "0.5.0"

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
        // Deliberately not wired into `check`: reference documentation is an artefact to publish, not
        // a gate to pass, and building it on every check costs every contributor time for output
        // nobody reads locally. Run `./gradlew dokkaGenerate` when it is wanted.
        apply(plugin = "org.jetbrains.dokka")

        apply(plugin = "maven-publish")

        extensions.configure<PublishingExtension> {
            publications {
                register<MavenPublication>("maven") {
                    from(components["java"])
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
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/rcapraro/kdiff")
                    credentials {
                        username = providers.gradleProperty("gpr.user")
                            .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                            .orNull
                        password = providers.gradleProperty("gpr.key")
                            .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                            .orNull
                    }
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
