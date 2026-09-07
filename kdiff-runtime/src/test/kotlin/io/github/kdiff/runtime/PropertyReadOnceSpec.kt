package io.github.kdiff.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * A comparison reads each compared property once per instance.
 *
 * A property getter can be arbitrarily expensive — a computed value, a lazily materialised collection —
 * and a redundant read is invisible in the diff, so nothing but a counting getter would notice one.
 */
class PropertyReadOnceSpec :
    FunSpec({

        test("each compared property is read exactly once from each side") {
            val before = Counted("a", "x", listOf(Keyed("K1", "one")))
            val after = Counted("b", "y", listOf(Keyed("K1", "two")))

            CountedDiffer.diff(before, after)

            before.nameReads shouldBe 1
            before.cityReads shouldBe 1
            after.nameReads shouldBe 1
            after.cityReads shouldBe 1
        }

        test("a property is read once even when it did not differ") {
            val before = Counted("a", "x", emptyList())
            val after = Counted("a", "x", emptyList())

            CountedDiffer.diff(before, after)

            before.nameReads shouldBe 1
            after.nameReads shouldBe 1
        }

        test("a property excluded from comparison is never read") {
            val before = Counted("a", "x", emptyList())
            val after = Counted("b", "x", emptyList())

            CountedDiffer.diff(before, after)

            before.ignoredReads shouldBe 0
            after.ignoredReads shouldBe 0
        }

        test("a keyed list derives each element's key once per side") {
            val before = Counted("a", "x", listOf(Keyed("K1", "one"), Keyed("K2", "two")))
            val after = Counted("a", "x", listOf(Keyed("K2", "two"), Keyed("K1", "changed")))

            CountedDiffer.diff(before, after)

            (before.entries + after.entries).map { it.keyReads } shouldBe listOf(1, 1, 1, 1)
        }

        test("a nested property is read once however many changes it reports") {
            val before = Nesting(Counted("a", "x", emptyList()))
            val after = Nesting(Counted("b", "y", emptyList()))

            NestingDiffer.diff(before, after).changes.size shouldBe 2
            before.innerReads shouldBe 1
            after.innerReads shouldBe 1
        }

        test("a hand-written differ reads no more than the equivalent explicit one") {
            val before = Counted("a", "x", emptyList())
            val after = Counted("b", "y", emptyList())
            val builtBefore = Counted("a", "x", emptyList())
            val builtAfter = Counted("b", "y", emptyList())

            CountedDiffer.diff(before, after)
            BuiltCountedDiffer.diff(builtBefore, builtAfter)

            builtBefore.nameReads shouldBe before.nameReads
            builtBefore.cityReads shouldBe before.cityReads
            builtAfter.nameReads shouldBe after.nameReads
            builtAfter.cityReads shouldBe after.cityReads
        }
    })

internal class Keyed(private val rawId: String, val label: String) {
    var keyReads = 0
        private set

    val id: String
        get() {
            keyReads++
            return rawId
        }
}

internal object KeyedDiffer : Differ<Keyed> {
    override fun diff(before: Keyed, after: Keyed): Diff = Diff(
        buildList { compareValue("label", before.label, after.label) },
    )
}

internal class Counted(private val rawName: String, private val rawCity: String, val entries: List<Keyed>) {
    var nameReads = 0
        private set
    var cityReads = 0
        private set
    var ignoredReads = 0
        private set

    val name: String
        get() {
            nameReads++
            return rawName
        }

    val city: String
        get() {
            cityReads++
            return rawCity
        }

    /** Named in no differ, standing in for a `@DiffIgnore` property. */
    val ignored: String
        get() {
            ignoredReads++
            return "ignored"
        }
}

internal object CountedDiffer : Differ<Counted> {
    override fun diff(before: Counted, after: Counted): Diff = Diff(
        buildList {
            compareValue("name", before.name, after.name)
            compareValue("city", before.city, after.city)
            compareKeyedList("entries", "id", before.entries, after.entries, KeyedDiffer) { it.id }
        },
    )
}

/** The same comparison reached through the builder, so both routes can be counted against each other. */
internal object BuiltCountedDiffer : Differ<Counted> by differ({
    field(Counted::name)
    field(Counted::city)
})

internal class Nesting(private val rawInner: Counted) {
    var innerReads = 0
        private set

    val inner: Counted
        get() {
            innerReads++
            return rawInner
        }
}

internal object NestingDiffer : Differ<Nesting> {
    override fun diff(before: Nesting, after: Nesting): Diff = Diff(
        buildList { compareNested("inner", before.inner, after.inner, CountedDiffer) },
    )
}
