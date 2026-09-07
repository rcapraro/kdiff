package io.github.kdiff.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class DiffSpec :
    FunSpec({

        test("a diff with no changes reports itself as empty") {
            val diff = Diff(emptyList())

            diff.changes shouldBe emptyList()
            diff.isEmpty shouldBe true
        }

        test("two diffs built from the same changes are equal") {
            val changes = listOf(ValueChanged(FieldPath.of("name"), "Ada", "Grace"))

            Diff(changes) shouldBe Diff(changes)
            Diff(changes).hashCode() shouldBe Diff(changes).hashCode()
        }

        test("a field path is a value, so paths with the same segments are equal") {
            val left = FieldPath(listOf(Segment.Field("address"), Segment.Field("street")))
            val right = FieldPath(listOf(Segment.Field("address"), Segment.Field("street")))

            left shouldBe right
        }

        test("a property path renders with dots") {
            FieldPath(listOf(Segment.Field("address"), Segment.Field("street")))
                .toString() shouldBe "address.street"
        }

        test("an index path renders with brackets") {
            FieldPath(listOf(Segment.Field("tags"), Segment.Index(2)))
                .toString() shouldBe "tags[2]"
        }

        test("a key path renders with the key property and value") {
            FieldPath(listOf(Segment.Field("addresses"), Segment.Key("id", "A2")))
                .toString() shouldBe "addresses[id=A2]"
        }

        test("a non-string key is retained as its own value and still renders the same") {
            val path = FieldPath(listOf(Segment.Field("rates"), Segment.Key("key", 1)))

            (path.segments[1] as Segment.Key).value shouldBe 1
            path.toString() shouldBe "rates[key=1]"
        }

        test("a key path followed by a property renders both") {
            FieldPath(listOf(Segment.Field("addresses"), Segment.Key("id", "A2"), Segment.Field("street")))
                .toString() shouldBe "addresses[id=A2].street"
        }

        test("every change subtype relocates itself when prefixed") {
            val at = FieldPath.of("street")
            val changes = listOf(
                ValueChanged(at, "x", "y"),
                Added(at, "x"),
                Removed(at, "x"),
                TypeChanged(at, "Card", "Transfer", "card", "transfer"),
                Moved(at, 0, 1),
            )

            val prefixed = changes.map { it.prefixedWith(Segment.Field("address")) }

            prefixed.map { it.path.toString() } shouldContainExactly List(5) { "address.street" }
        }

        test("prefixing survives three levels of nesting for every change subtype") {
            val at = FieldPath.of("city")
            val changes = listOf(
                ValueChanged(at, "Paris", "Lyon"),
                Added(at, "Lyon"),
                Removed(at, "Paris"),
                TypeChanged(at, "A", "B", "a", "b"),
                Moved(at, 1, 0),
            )

            val lifted = changes
                .map { it.prefixedWith(Segment.Field("address")) }
                .map { it.prefixedWith(Segment.Field("company")) }

            lifted.map { it.path.toString() } shouldContainExactly List(5) { "company.address.city" }
        }

        test("prefixing preserves each change's own data") {
            val prefixed = ValueChanged(FieldPath.of("street"), "old", "new")
                .prefixedWith(Segment.Field("address"))

            prefixed.before shouldBe "old"
            prefixed.after shouldBe "new"
        }

        test("dropping the first segment leaves the rest of the path") {
            FieldPath(listOf(Segment.Field("address"), Segment.Field("street")))
                .withoutFirst() shouldBe FieldPath.of("street")
        }

        test("dropping the first segment of a single-segment path leaves the root path") {
            FieldPath.of("street").withoutFirst() shouldBe FieldPath.ROOT
        }

        test("dropping the first segment of the root path leaves the root path") {
            FieldPath.ROOT.withoutFirst() shouldBe FieldPath.ROOT
        }

        test("dropping a segment is the inverse of prefixing one") {
            val path = FieldPath(listOf(Segment.Field("addresses"), Segment.Key("id", "A2"), Segment.Field("street")))

            path.prefixedWith(Segment.Field("company")).withoutFirst() shouldBe path
        }

        test("every change subtype descends when its root is dropped") {
            val at = FieldPath(listOf(Segment.Field("address"), Segment.Field("street")))
            val changes = listOf(
                ValueChanged(at, "x", "y"),
                Added(at, "x"),
                Removed(at, "x"),
                TypeChanged(at, "Card", "Transfer", "card", "transfer"),
                Moved(at, 0, 1),
            )

            val descended = changes.map { it.withoutRoot() }

            descended.map { it.path.toString() } shouldContainExactly List(5) { "street" }
        }

        test("descending keeps each change's own type and data") {
            val at = FieldPath(listOf(Segment.Field("address"), Segment.Field("street")))

            val value = ValueChanged(at, "old", "new").withoutRoot()
            val added = Added(at, "x").withoutRoot()
            val removed = Removed(at, "x").withoutRoot()
            val typed = TypeChanged(at, "Card", "Transfer", "card", "transfer").withoutRoot()
            val moved = Moved(at, 0, 1).withoutRoot()

            value shouldBe ValueChanged(FieldPath.of("street"), "old", "new")
            added shouldBe Added(FieldPath.of("street"), "x")
            removed shouldBe Removed(FieldPath.of("street"), "x")
            typed shouldBe TypeChanged(FieldPath.of("street"), "Card", "Transfer", "card", "transfer")
            moved shouldBe Moved(FieldPath.of("street"), 0, 1)
        }
    })
