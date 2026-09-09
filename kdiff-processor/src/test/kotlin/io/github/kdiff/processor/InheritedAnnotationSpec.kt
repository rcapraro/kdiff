package io.github.kdiff.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.github.kdiff.runtime.FieldPath
import io.github.kdiff.runtime.PatchFailure
import io.github.kdiff.runtime.Segment
import io.github.kdiff.runtime.ValueChanged
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A comparison annotation a sealed parent declares is honoured by a subclass that overrides it.
 *
 * Compiled and invoked rather than asserted on generated text: the claim is what the differ reports,
 * and the two dispatch branches have to agree about it.
 */
private fun sealedModel(parentProperty: String, subclassProperty: String, fixture: String): SourceFile =
    SourceFile.kotlin(
        "Doc.kt",
        """
        package demo

        import io.github.kdiff.annotations.Diffable
        import io.github.kdiff.annotations.DiffAsValue
        import io.github.kdiff.annotations.DiffIgnore

        @Diffable
        data class Meta(val title: String, val revision: String)

        @Diffable
        sealed interface Doc {
            $parentProperty
        }

        @Diffable
        data class Letter($subclassProperty, val body: String) : Doc

        @Diffable
        data class Memo(override val meta: Meta, val to: String) : Doc

        object Fixture {
            $fixture
        }
        """.trimIndent(),
    )

private const val META_AS_VALUE = "@DiffAsValue val meta: Meta"

private const val LETTER_META = "override val meta: Meta"

/** Two `Letter`s of one subclass, differing only inside `meta`. */
private const val SAME_SUBCLASS = """
    val before: Doc = Letter(Meta("Draft", "r1"), "hello")
    val after: Doc = Letter(Meta("Final", "r1"), "hello")
"""

/** A swap between two subclasses, both carrying the parent's property. */
private const val SUBCLASS_SWAP = """
    val before: Doc = Letter(Meta("Draft", "r1"), "hello")
    val after: Doc = Memo(Meta("Final", "r1"), "ops")
"""

class InheritedAnnotationSpec :
    FunSpec({

        test("a value declaration on a sealed parent property is honoured by a subclass") {
            val result = compile(sealedModel(META_AS_VALUE, LETTER_META, SAME_SUBCLASS))

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val diff = result.diffFixture("demo.DocDiffer")
            diff.changes.map { it.path.toString() } shouldContainExactly listOf("meta")

            val change = diff.changes.single()
            check(change is ValueChanged) { "expected a value change but was $change" }
            "${change.before}" shouldBe "Meta(title=Draft, revision=r1)"
            "${change.after}" shouldBe "Meta(title=Final, revision=r1)"
        }

        test("the same subclass and a subclass swap agree about the parent's property") {
            val result = compile(sealedModel(META_AS_VALUE, LETTER_META, SUBCLASS_SWAP))

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val paths = result.diffFixture("demo.DocDiffer").changes.map { it.path.toString() }
            paths shouldContainExactly listOf("", "meta")
        }

        test("an exclusion on a sealed parent property is honoured by a subclass") {
            val result = compile(
                sealedModel(
                    parentProperty = "@DiffIgnore val meta: Meta",
                    subclassProperty = LETTER_META,
                    fixture = SAME_SUBCLASS,
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            result.diffFixture("demo.DocDiffer").changes.shouldBeEmpty()
        }

        test("a hand-written differ named on a sealed parent property is honoured by a subclass") {
            val result = compile(
                SourceFile.kotlin(
                    "Doc.kt",
                    """
                    package demo

                    import io.github.kdiff.annotations.Diffable
                    import io.github.kdiff.annotations.DiffWith
                    import io.github.kdiff.runtime.Differ
                    import io.github.kdiff.runtime.differ

                    data class Weight(val grams: String)

                    object WeightDiffer : Differ<Weight> by differ({ field(Weight::grams) })

                    @Diffable
                    sealed interface Doc {
                        @DiffWith(WeightDiffer::class) val weight: Weight
                    }

                    @Diffable
                    data class Letter(override val weight: Weight, val body: String) : Doc

                    object Fixture {
                        val before: Doc = Letter(Weight("500"), "hello")
                        val after: Doc = Letter(Weight("600"), "hello")
                    }
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            result.diffFixture("demo.DocDiffer").changes.map { it.path.toString() } shouldContainExactly
                listOf("weight.grams")
        }

        test("an annotation on the override wins over one on the overridee") {
            val result = compile(
                sealedModel(
                    parentProperty = META_AS_VALUE,
                    subclassProperty = "@DiffIgnore override val meta: Meta",
                    fixture = SAME_SUBCLASS,
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            result.diffFixture("demo.DocDiffer").changes.shouldBeEmpty()
        }

        test("an unannotated parent property still descends into the subclass's override") {
            val result = compile(
                sealedModel(
                    parentProperty = "val meta: Meta",
                    subclassProperty = LETTER_META,
                    fixture = SAME_SUBCLASS,
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            result.diffFixture("demo.DocDiffer").changes.map { it.path.toString() } shouldContainExactly
                listOf("meta.title")
        }

        test("a property inheriting its value declaration round-trips") {
            val result = compile(sealedModel(META_AS_VALUE, LETTER_META, SAME_SUBCLASS))

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val applied = result.roundTripFixture("demo.DocDiffer")
            applied.failures.shouldBeEmpty()
            applied.value shouldBe result.fixtureAfter()
        }

        test("a change beneath a property inheriting its value declaration is reported as a failure") {
            val result = compile(sealedModel(META_AS_VALUE, LETTER_META, SAME_SUBCLASS))

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val beneath = ValueChanged(
                FieldPath(listOf(Segment.Field("meta"), Segment.Field("title"))),
                "Draft",
                "Final",
            )
            val applied = result.patchFixture("demo.DocDiffer", listOf(beneath))

            applied.failures.single().reason shouldBe PatchFailure.Reason.NotApplicableToValue
        }

        test("tracking a property excluded by the declaration it overrides is the same conflict") {
            val result = compile(
                SourceFile.kotlin(
                    "Doc.kt",
                    """
                    package demo

                    import io.github.kdiff.annotations.Diffable
                    import io.github.kdiff.annotations.DiffIgnore
                    import io.github.kdiff.annotations.TrackDepth
                    import io.github.kdiff.annotations.Trackable

                    @Diffable
                    sealed interface Doc {
                        @DiffIgnore val revision: String
                    }

                    @Diffable
                    @Trackable
                    data class Letter(@TrackDepth(1) override val revision: String, val body: String) : Doc
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            result.messages shouldContain
                "@TrackDepth on revision conflicts with @DiffIgnore; an ignored property produces no " +
                "changes and so can never be tracked"
        }

        test("a subclass inheriting a value declaration is not itself rejected for it") {
            val result = compile(
                SourceFile.kotlin(
                    "Doc.kt",
                    """
                    package demo

                    import io.github.kdiff.annotations.Diffable
                    import io.github.kdiff.annotations.DiffAsValue

                    @Diffable
                    sealed interface Doc {
                        @DiffAsValue val title: String
                    }

                    @Diffable
                    data class Letter(override val title: String, val body: String) : Doc

                    object Fixture {
                        val before: Doc = Letter("Draft", "hello")
                        val after: Doc = Letter("Final", "hello")
                    }
                    """.trimIndent(),
                ),
            )

            // `title` is a `String`, already compared as a value, so the parent's own declaration is
            // the pointless one — reported once, against `Doc`, and never against a subclass that
            // wrote nothing (design D4).
            result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            result.messages.split("@DiffAsValue on title has no effect").size - 1 shouldBe 1
        }
    })
