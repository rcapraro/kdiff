package io.github.kdiff.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Lifting a path and descending it again are the two operations every nested comparison and every
 * nested reconstruction perform once per level, so they were rebuilt to allocate less. This holds them
 * to producing exactly what the straightforward forms produced.
 */
class FieldPathLiftingSpec : FunSpec({

    val root = Segment.Field("order")
    val element = Segment.Key("id", "A1")
    val deep = FieldPath(listOf(Segment.Field("a"), Segment.Index(0), Segment.Field("b"), Segment.Field("c")))

    context("lifting a path") {
        test("one segment is prepended") {
            deep.prefixedWith(root).segments shouldBe listOf(root) + deep.segments
        }

        test("two segments give what two single lifts give") {
            deep.prefixedWith(root, element) shouldBe deep.prefixedWith(element).prefixedWith(root)
        }

        test("lifting the root path yields a path of just that segment") {
            FieldPath.ROOT.prefixedWith(root) shouldBe FieldPath.of("order")
        }

        test("a lifted path renders as the caller would write it") {
            deep.prefixedWith(root, element).toString() shouldBe "order[id=A1].a[0].b.c"
        }
    }

    context("lifting a change") {
        val at = FieldPath.of("city")

        test("every variant keeps its own type and gains both segments") {
            val lifted = listOf(
                ValueChanged(at, "old", "new"),
                Added(at, "x"),
                Removed(at, "x"),
                TypeChanged(at, "Card", "Transfer", "card", "transfer"),
                Moved(at, 0, 1),
            ).map { it.prefixedWith(root, element) }

            lifted.map { it::class } shouldBe listOf(
                ValueChanged::class,
                Added::class,
                Removed::class,
                TypeChanged::class,
                Moved::class,
            )
            lifted.map { it.path.toString() } shouldBe List(5) { "order[id=A1].city" }
        }

        test("a two-segment lift equals the two single lifts it replaces") {
            val change = ValueChanged(at, "old", "new")

            change.prefixedWith(root, element) shouldBe change.prefixedWith(element).prefixedWith(root)
        }
    }

    context("descending a path") {
        test("the first segment is dropped") {
            deep.withoutFirst() shouldBe FieldPath(deep.segments.drop(1))
        }

        test("a descended path equals, hashes and renders as the copy it replaces") {
            val descended = deep.withoutFirst()
            val copied = FieldPath(deep.segments.drop(1))

            descended shouldBe copied
            descended.hashCode() shouldBe copied.hashCode()
            descended.toString() shouldBe copied.toString()
        }

        test("descending repeatedly from a six-segment path yields each level in turn") {
            val six = FieldPath(
                listOf(
                    Segment.Field("a"),
                    Segment.Field("b"),
                    Segment.Index(2),
                    Segment.Field("c"),
                    Segment.Key("id", 7),
                    Segment.Field("d"),
                ),
            )

            val levels = generateSequence(six) { it.withoutFirst().takeIf { next -> next.segments.isNotEmpty() } }
                .map { it.toString() }
                .toList()

            levels shouldBe listOf("a.b[2].c[id=7].d", "b[2].c[id=7].d", "[2].c[id=7].d", "c[id=7].d", "[id=7].d", "d")
        }

        // `drop` tolerates having nothing to drop and `subList` does not, so the root path is the case
        // the view has to special-case.
        test("descending the root path leaves the root path") {
            FieldPath.ROOT.withoutFirst() shouldBe FieldPath.ROOT
        }

        test("descending a one-segment path leaves the root path") {
            FieldPath.of("city").withoutFirst() shouldBe FieldPath.ROOT
        }
    }
})
