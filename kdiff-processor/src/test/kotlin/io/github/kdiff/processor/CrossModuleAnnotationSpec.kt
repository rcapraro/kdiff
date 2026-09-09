package io.github.kdiff.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * A comparison annotation inherited across a module boundary.
 *
 * A shared domain module can depend on `kdiff-annotations` without applying the processor, leaving
 * generation to whoever consumes it. Its declarations then reach the consumer as class files, which
 * have **no containing file** — so anything using the presence of a file to mean "the annotation is
 * written here" gets the answer backwards, and reports a local override for an annotation it does not
 * carry.
 */
private val upstream = SourceFile.kotlin(
    "Shared.kt",
    """
    package shared

    import io.github.kdiff.annotations.DiffAsValue
    import io.github.kdiff.annotations.DiffWith
    import io.github.kdiff.runtime.Differ
    import io.github.kdiff.runtime.differ

    data class Weight(val grams: String)

    object WeightDiffer : Differ<Weight> by differ({ field(Weight::grams) })

    /** Redundant: a `String` already compares as a value. Nothing here reads it, so nothing rejects it. */
    interface HasCode {
        @DiffAsValue val code: String
    }

    interface HasWeight {
        @DiffWith(WeightDiffer::class) val weight: Weight
    }

    interface HasBroken {
        @DiffWith(Weight::class) val broken: Weight
    }
    """.trimIndent(),
)

class CrossModuleAnnotationSpec :
    FunSpec({

        test("a redundant value declaration inherited from a class file is not reported against the override") {
            val result = compileAgainst(
                compileDependency(upstream),
                SourceFile.kotlin(
                    "Parcel.kt",
                    """
                    package demo

                    import io.github.kdiff.annotations.Diffable
                    import shared.HasCode

                    @Diffable
                    data class Parcel(override val code: String, val tracking: String) : HasCode
                    """.trimIndent(),
                ),
            )

            // The annotation is redundant, not wrong: `code` compares as a value either way. Reporting
            // it would name an override that carries nothing, and refusing would leave the consumer
            // with no differ over an upstream declaration it cannot edit.
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            result.messages shouldNotContain "has no effect"
            result.generatedFileNames shouldBe listOf("ParcelDiff.kt")
        }

        test("a usable differ inherited from a class file is honoured") {
            val result = compileAgainst(
                compileDependency(upstream),
                SourceFile.kotlin(
                    "Crate.kt",
                    """
                    package demo

                    import io.github.kdiff.annotations.Diffable
                    import shared.HasWeight
                    import shared.Weight

                    @Diffable
                    data class Crate(override val weight: Weight, val tracking: String) : HasWeight

                    object Fixture {
                        val before = Crate(Weight("500"), "T-1")
                        val after = Crate(Weight("600"), "T-1")
                    }
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            result.diffFixture("demo.CrateDiffer").changes.map { it.path.toString() } shouldBe
                listOf("weight.grams")
        }

        test("an unusable differ inherited from a class file is reported rather than silently dropped") {
            val result = compileAgainst(
                compileDependency(upstream),
                SourceFile.kotlin(
                    "Pallet.kt",
                    """
                    package demo

                    import io.github.kdiff.annotations.Diffable
                    import shared.HasBroken
                    import shared.Weight

                    @Diffable
                    data class Pallet(override val broken: Weight, val tracking: String) : HasBroken
                    """.trimIndent(),
                ),
            )

            // Nobody upstream read the annotation, so nobody upstream rejected it. Suppressing the
            // message here would leave a property kdiff cannot compare and no diagnostic saying so,
            // which the processor's conventions forbid.
            result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            result.messages shouldContain "@DiffWith requires an object; Weight is not one"
        }
    })
