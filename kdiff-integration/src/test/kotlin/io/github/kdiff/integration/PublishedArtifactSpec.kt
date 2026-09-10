package io.github.kdiff.integration

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.TaskOutcome

/**
 * A consumer build declaring only the three published coordinates compiles and runs generated code.
 *
 * What this reaches that nothing else in the repository does: the generated descriptors, whether three
 * coordinates are sufficient, and the processor loaded from a jar through its service registration.
 * `kdiff-sample` proves the processor works; this proves a *release* does.
 */
class PublishedArtifactSpec :
    FunSpec({

        test("a consumer declaring only the three coordinates compiles and runs the generated differ") {
            val consumer = ConsumerProject(tempdir())
            consumer.write()
            consumer.main("Model.kt", MODEL)
            consumer.main("Verify.kt", USAGE)

            val result = consumer.build("verify")

            result.task(":verify")?.outcome shouldBe TaskOutcome.SUCCESS
            consumer.generated("Order")!!.isFile shouldBe true
        }

        // The gate is worth nothing unless removing a coordinate breaks it, so removing one is a test.
        test("dropping the processor coordinate leaves the differ unresolved") {
            val consumer = ConsumerProject(tempdir())
            consumer.write()
            consumer.main("Model.kt", MODEL)
            consumer.main("Verify.kt", USAGE)
            consumer.withoutProcessor()

            val result = consumer.buildAndFail("verify")

            result.output shouldContain "OrderDiffer"
        }

        // The version under test is also on Maven Central, so without the `exclusiveContent` filter a
        // plain `mavenCentral()` fallback would answer for artefacts this build failed to produce —
        // and every test above would pass having proved nothing about the working tree. Pointed at an
        // empty repository, resolution must fail rather than reach Central.
        test("kdiff resolves only from the repository this build published to") {
            val consumer = ConsumerProject(tempdir())
            consumer.write(repository = tempdir().absolutePath)
            consumer.main("Model.kt", MODEL)

            val result = consumer.buildAndFail("compileKotlin")

            result.output shouldContain "kdiff-runtime:$version"
        }

        // The processor declares KotlinPoet and the symbol-processing API, and its descriptor carries
        // both. On the `ksp` configuration that is harmless — but "harmless" is a claim, and this is
        // what makes it one.
        //
        // Asserted by compiling against them rather than by resolving a configuration: naming a type a
        // consumer should not have is a shorter statement than reading Gradle's resolution result, and
        // it does not couple the test to Gradle's API.
        test("the code generator's own dependencies are not on the consumer's compile classpath") {
            val consumer = ConsumerProject(tempdir())
            consumer.write()
            consumer.main("Model.kt", MODEL)
            consumer.main(
                "Reach.kt",
                """
                package consumer

                import com.squareup.kotlinpoet.FileSpec

                val leaked: FileSpec? = null
                """,
            )

            val result = consumer.buildAndFail("compileKotlin")

            result.output shouldContain "Unresolved reference"
        }
    })
