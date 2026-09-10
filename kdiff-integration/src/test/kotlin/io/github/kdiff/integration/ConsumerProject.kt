package io.github.kdiff.integration

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.io.File

/**
 * A Gradle project that consumes kdiff the way a reader of the README does: three coordinates, the KSP
 * plugin, and nothing of this repository's own.
 *
 * Written to a directory and built with TestKit, so what is exercised is the published descriptors and
 * the processor loaded from a jar through its service registration — none of which a
 * `project(":kdiff-processor")` dependency touches.
 */
private fun property(name: String): String =
    System.getProperty(name) ?: error("$name is not set; the test must be run through Gradle")

internal val group: String = property("kdiff.group")
internal val version: String = property("kdiff.version")
private val buildRepo: String = property("kdiff.buildRepo")
private val kotlinVersion: String = property("kdiff.kotlinVersion")
private val kspVersion: String = property("kdiff.kspVersion")
private val gradleUserHome: String = property("kdiff.gradleUserHome")

/**
 * The coordinates a consumer declares, in the order the README declares them.
 *
 * `ksp` rather than `implementation` for the processor is the whole point of the third line: it is what
 * keeps a code generator off a consumer's runtime classpath.
 */
private val DEPENDENCIES = """
    implementation("$group:kdiff-annotations:$version")
    implementation("$group:kdiff-runtime:$version")
    ksp("$group:kdiff-processor:$version")
""".trimIndent()

/**
 * kdiff resolves **only** from the repository this build published to.
 *
 * `exclusiveContent` rather than repository order: released versions of these coordinates exist on
 * Maven Central, so a plain `mavenCentral()` fallback would let Central answer for an artefact this
 * build failed to produce — and the test would pass having proved nothing about the working tree.
 * Everything else, the Kotlin standard library included, still comes from Central.
 *
 * [repository] arrives as a URI rather than a path, because the generated file is a Kotlin script and
 * an absolute path is not a valid Kotlin string literal everywhere it can be spelled — a backslash in
 * one is an escape sequence. A `file:` URI carries the same location with nothing in it to escape.
 */
private fun settings(repository: String) = """
    pluginManagement {
        repositories {
            gradlePluginPortal()
            mavenCentral()
        }
    }

    dependencyResolutionManagement {
        repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
        repositories {
            exclusiveContent {
                forRepository { maven { url = uri("$repository") } }
                filter { includeGroup("$group") }
            }
            mavenCentral()
        }
    }

    rootProject.name = "consumer"
""".trimIndent()

private val BUILD = """
    plugins {
        kotlin("jvm") version "$kotlinVersion"
        id("com.google.devtools.ksp") version "$kspVersion"
    }

    kotlin {
        jvmToolchain(21)
    }

    dependencies {
$DEPENDENCIES
    }

    // The generated code is exercised by running it, not by a test framework. `kotlin("test")` would
    // be a fourth coordinate that no module in this repository uses, so a Gradle user home populated by
    // `./gradlew check` would not hold it and the first run of this gate on a fresh machine would need
    // the network — which the aggregate command is required not to.
    tasks.register<JavaExec>("verify") {
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("consumer.VerifyKt")
    }
""".trimIndent()

internal class ConsumerProject(private val root: File) {

    /**
     * [repository] defaults to what this build published. A test overrides it to show that the
     * `exclusiveContent` filter is load-bearing rather than decorative.
     */
    fun write(repository: String = buildRepo) {
        root.mkdirs()
        root.resolve("settings.gradle.kts").writeText(settings(File(repository).toURI().toString()))
        root.resolve("build.gradle.kts").writeText(BUILD)
    }

    /**
     * Rewrites the build script with the processor coordinate removed, so that the gate can be shown
     * to fail when it should. The KSP plugin stays applied: what is being taken away is the processor,
     * not the machinery that runs it.
     */
    fun withoutProcessor() {
        val withoutKsp = DEPENDENCIES.lines().filterNot { it.trimStart().startsWith("ksp(") }
        root.resolve("build.gradle.kts")
            .writeText(BUILD.replace(DEPENDENCIES, withoutKsp.joinToString("\n")))
    }

    /** Writes [content] to `src/main/kotlin/<name>`, creating the source root the first time. */
    fun main(name: String, content: String): Unit = source("src/main/kotlin/$name", content)

    private fun source(path: String, content: String) {
        val file = root.resolve(path)
        file.parentFile.mkdirs()
        file.writeText(content.trimIndent())
    }

    /** The generated file for [type], or null when the build has not produced one. */
    fun generated(type: String): File? =
        root.resolve("build/generated/ksp/main/kotlin/consumer/${type}Diff.kt").takeIf { it.isFile }

    fun build(vararg arguments: String): BuildResult = runner(*arguments).build()

    fun buildAndFail(vararg arguments: String): BuildResult = runner(*arguments).buildAndFail()

    private fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(root)
        // Not `withPluginClasspath`: the point is that the plugins and the coordinates resolve the way
        // a consumer's do.
        .withArguments(*arguments, "--stacktrace", "--gradle-user-home", gradleUserHome)
        .forwardOutput()
}
