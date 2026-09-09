package io.github.kdiff.processor

import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Which declaration a property's comparison annotations are read from.
 *
 * Each hierarchy level lives in its own file, because the recording asserts the file as well as the
 * owner: the file is what has to join the originating set when the annotation came from elsewhere.
 */
private val meta = SourceFile.kotlin(
    "Meta.kt",
    """
    package demo

    import io.github.kdiff.annotations.Diffable

    @Diffable
    data class Meta(val title: String)
    """.trimIndent(),
)

private fun root(property: String): SourceFile = SourceFile.kotlin(
    "Doc.kt",
    """
    package demo

    import io.github.kdiff.annotations.DiffAsValue
    import io.github.kdiff.annotations.DiffIgnore
    import io.github.kdiff.annotations.DiffWith

    interface Doc {
        $property
    }
    """.trimIndent(),
)

private fun middle(property: String): SourceFile = SourceFile.kotlin(
    "Draft.kt",
    """
    package demo

    import io.github.kdiff.annotations.DiffAsValue
    import io.github.kdiff.annotations.DiffIgnore

    interface Draft : Doc {
        $property
    }
    """.trimIndent(),
)

private fun leaf(superType: String, property: String): SourceFile = SourceFile.kotlin(
    "Letter.kt",
    """
    package demo

    import io.github.kdiff.annotations.DiffAsValue
    import io.github.kdiff.annotations.DiffIgnore

    data class Letter($property, val body: String) : $superType
    """.trimIndent(),
)

class ComparisonDeclarationSpec :
    FunSpec({

        test("a property carrying its own annotation is its own comparison declaration") {
            val recorded = recordResolutions(
                meta,
                root("val meta: Meta"),
                leaf("Doc", "@DiffAsValue override val meta: Meta"),
            )

            val letter = recorded.of("Letter", "meta")
            letter.comparisonOwner shouldBe "Letter"
            letter.comparisonSourceFileName shouldBe "Letter.kt"
        }

        test("a plain override reads the annotation off the property it overrides") {
            val recorded = recordResolutions(
                meta,
                root("@DiffAsValue val meta: Meta"),
                leaf("Doc", "override val meta: Meta"),
            )

            val letter = recorded.of("Letter", "meta")
            letter.comparisonOwner shouldBe "Doc"
            letter.comparisonSourceFileName shouldBe "Doc.kt"
        }

        test("a plain override does not cancel a grandparent's annotation") {
            val recorded = recordResolutions(
                meta,
                root("@DiffAsValue val meta: Meta"),
                middle("override val meta: Meta"),
                leaf("Draft", "override val meta: Meta"),
            )

            val letter = recorded.of("Letter", "meta")
            letter.comparisonOwner shouldBe "Doc"
            letter.comparisonSourceFileName shouldBe "Doc.kt"
        }

        test("the nearest annotated declaration in the chain wins") {
            val recorded = recordResolutions(
                meta,
                root("@DiffAsValue val meta: Meta"),
                middle("@DiffIgnore override val meta: Meta"),
                leaf("Draft", "override val meta: Meta"),
            )

            recorded.of("Letter", "meta").comparisonOwner shouldBe "Draft"
        }

        test("an annotation on the override wins over one on the overridee") {
            val recorded = recordResolutions(
                meta,
                root("@DiffAsValue val meta: Meta"),
                leaf("Doc", "@DiffIgnore override val meta: Meta"),
            )

            recorded.of("Letter", "meta").comparisonOwner shouldBe "Letter"
        }

        test("an unannotated property anywhere in the chain stays its own declaration") {
            val recorded = recordResolutions(
                meta,
                root("val meta: Meta"),
                leaf("Doc", "override val meta: Meta"),
            )

            val letter = recorded.of("Letter", "meta")
            letter.comparisonOwner shouldBe "Letter"
            letter.comparisonSourceFileName shouldBe "Letter.kt"
        }

        test("an inherited annotation's file is consulted, so removing it regenerates the reader") {
            val recorded = recordResolutions(
                meta,
                root("@DiffAsValue val meta: Meta"),
                leaf("Doc", "override val meta: Meta"),
            )

            recorded.of("Letter", "meta").consultedFileNames shouldBe listOf("Letter.kt", "Doc.kt")
        }

        test("a property annotated nowhere still consults the chain, so adding one reaches the reader") {
            val recorded = recordResolutions(
                meta,
                root("val meta: Meta"),
                leaf("Doc", "override val meta: Meta"),
            )

            // The search read the *absence* of an annotation on `Doc.meta`. Recording only the
            // declaration it settled on would record nothing, and adding `@DiffAsValue` to `Doc` later
            // would leave `LetterDiff.kt` stale.
            recorded.of("Letter", "meta").consultedFileNames shouldBe listOf("Letter.kt", "Doc.kt")
        }

        test("a property excluded by an inherited annotation still consults the chain") {
            val recorded = recordResolutions(
                meta,
                root("@DiffIgnore val meta: Meta"),
                leaf("Doc", "override val meta: Meta"),
            )

            // Nothing else can record these: an excluded property is filtered out before resolution and
            // produces no comparison to carry its files.
            recorded.of("Letter", "meta").consultedFileNames shouldBe listOf("Letter.kt", "Doc.kt")
        }

        test("the chain stops at the nearest annotated declaration, consulting nothing above it") {
            val recorded = recordResolutions(
                meta,
                root("@DiffAsValue val meta: Meta"),
                middle("@DiffIgnore override val meta: Meta"),
                leaf("Draft", "override val meta: Meta"),
            )

            recorded.of("Letter", "meta").consultedFileNames shouldBe listOf("Letter.kt", "Draft.kt")
        }

        test("a property carrying its own annotation consults only its own file") {
            val recorded = recordResolutions(
                meta,
                root("val meta: Meta"),
                leaf("Doc", "@DiffAsValue override val meta: Meta"),
            )

            recorded.of("Letter", "meta").consultedFileNames shouldBe listOf("Letter.kt")
        }

        test("a property that overrides nothing consults only its own file") {
            val recorded = recordResolutions(
                meta,
                root("val meta: Meta"),
                leaf("Doc", "override val meta: Meta"),
            )

            recorded.of("Letter", "body").consultedFileNames shouldBe listOf("Letter.kt")
        }

        test("a property that overrides nothing is its own declaration") {
            val recorded = recordResolutions(
                meta,
                root("val meta: Meta"),
                leaf("Doc", "override val meta: Meta"),
            )

            recorded.of("Letter", "body").comparisonOwner shouldBe "Letter"
        }
    })
