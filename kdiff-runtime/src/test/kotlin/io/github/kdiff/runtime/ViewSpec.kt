package io.github.kdiff.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun path(vararg names: String) = FieldPath(names.map { Segment.Field(it) })

class TreeSpec : FunSpec({

    test("changes sharing a prefix are grouped under a single node") {
        val diff = Diff(
            listOf(
                ValueChanged(path("address", "street"), "Rue X", "Rue Y"),
                ValueChanged(path("address", "city"), "Paris", "Lyon"),
            ),
        )

        val children = diff.tree().children

        children.size shouldBe 1
        children.single().segment shouldBe Segment.Field("address")
        children.single().children.size shouldBe 2
    }

    test("the tree of an empty diff is empty") {
        val tree = Diff(emptyList()).tree()

        tree.isEmpty shouldBe true
        tree.children shouldBe emptyList()
    }

    test("the tree holds exactly the changes the flat list holds") {
        val changes = listOf(
            ValueChanged(path("name"), "Ada", "Grace"),
            ValueChanged(path("address", "street"), "Rue X", "Rue Y"),
            ValueChanged(path("company", "address", "city"), "Paris", "Lyon"),
            Added(FieldPath(listOf(Segment.Field("tags"), Segment.Index(2))), "new"),
        )

        Diff(changes).tree().allChanges() shouldContainExactlyInAnyOrder changes
    }

    test("a change at the root sits on the root node rather than a child") {
        val change = TypeChanged(FieldPath.ROOT, "Card", "Transfer", "card", "transfer")

        val tree = Diff(listOf(change)).tree()

        tree.changes shouldBe listOf(change)
        tree.children shouldBe emptyList()
    }
})

class RenderSpec : FunSpec({

    test("a value change renders with its path and both values") {
        val rendered = Diff(
            listOf(ValueChanged(path("address", "street"), "1 Rue X", "2 Rue Y")),
        ).render()

        rendered shouldContain "address.street"
        rendered shouldContain "\"1 Rue X\""
        rendered shouldContain "\"2 Rue Y\""
    }

    test("each kind of change renders on its own line and is distinguishable") {
        val rendered = Diff(
            listOf(
                ValueChanged(path("name"), "Ada", "Grace"),
                Added(FieldPath(listOf(Segment.Field("addresses"), Segment.Key("id", "A3"))), "new"),
                Removed(FieldPath(listOf(Segment.Field("addresses"), Segment.Key("id", "A1"))), "old"),
                Moved(FieldPath(listOf(Segment.Field("addresses"), Segment.Key("id", "A2"))), 0, 1),
                TypeChanged(path("payment"), "Card", "Transfer", "card", "transfer"),
            ),
        ).render()

        rendered.lines().size shouldBe 5
        rendered shouldContain "\"Ada\" -> \"Grace\""
        rendered shouldContain "ADDED"
        rendered shouldContain "REMOVED"
        rendered shouldContain "MOVED 0 -> 1"
        rendered shouldContain "TYPE Card -> Transfer"
    }

    test("an empty diff renders without claiming a change") {
        Diff(emptyList()).render() shouldBe "no changes"
    }

    test("rendering does not alter the diff") {
        val diff = Diff(listOf(ValueChanged(path("name"), "Ada", "Grace")))
        val before = diff.changes.toList()

        diff.render()

        diff.changes shouldBe before
    }

    test("a null value renders as null rather than as a quoted string") {
        val rendered = Diff(listOf(ValueChanged(path("nickname"), null, "Ada"))).render()

        rendered shouldContain "null -> \"Ada\""
    }
})
