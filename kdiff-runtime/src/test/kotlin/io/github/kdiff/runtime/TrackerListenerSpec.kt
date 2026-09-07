package io.github.kdiff.runtime

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.FunSpec

/**
 * A tracker's report does not depend on which listeners are registered.
 *
 * Computing a change's two sides is now skipped when no per-field listener wants them, so the report a
 * tracker returns and the callbacks it fires are pinned independently of each other.
 */
class TrackerListenerSpec : FunSpec({

    val next = ORDER.copy(reference = "R2", status = "CLOSED")

    test("a tracker with no listener returns the same report as one with both") {
        val silent = tracker(OrderDiffer, ORDER).update(next)
        val listening = tracker(OrderDiffer, ORDER) {
            onFieldChange { _, _, _ -> }
            onChange { _, _, _ -> }
        }.update(next)

        silent.changes shouldBe listening.changes
    }

    test("a tracker with only a batched listener still reports every selected change") {
        var batched: List<Change> = emptyList()

        val reported = tracker(OrderDiffer, ORDER) { onChange { _, _, changes -> batched = changes } }
            .update(next)

        reported.paths() shouldContainExactly listOf("reference", "status")
        batched shouldBe reported.changes
    }

    test("per-field listeners fire once per selected change, in report order") {
        val seen = mutableListOf<String>()

        val reported = tracker(OrderDiffer, ORDER) {
            onFieldChange { path, before, after -> seen += "$path: $before -> $after" }
        }.update(next)

        seen shouldContainExactly listOf("reference: R1 -> R2", "status: OPEN -> CLOSED")
        seen.size shouldBe reported.changes.size
    }

    test("every registered per-field listener sees every selected change") {
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()

        tracker(OrderDiffer, ORDER) {
            onFieldChange { path, _, _ -> first += path.toString() }
            onFieldChange { path, _, _ -> second += path.toString() }
        }.update(next)

        first shouldContainExactly listOf("reference", "status")
        second shouldContainExactly first
    }

    test("no listener fires when the scope selects nothing") {
        var fired = false

        val reported = tracker(OrderDiffer, ORDER) {
            field(Order::note)
            onFieldChange { _, _, _ -> fired = true }
            onChange { _, _, _ -> fired = true }
        }.update(next)

        reported.changes shouldBe emptyList()
        fired shouldBe false
    }
})
