package io.github.kdiff.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private data class Entry(val id: String, val street: String)

/** A hand-rolled stand-in for what the processor generates, so the helpers can be tested alone. */
private object EntryPatcher :
    Differ<Entry> by differ({
        field(Entry::id)
        field(Entry::street)
    }),
    Patcher<Entry> {
    override fun apply(before: Entry, changes: List<Change>): PatchResult<Entry> {
        val grouped = groupByProperty(changes, setOf("id", "street"))
        val id = patchValue(before.id, grouped.forProperty("id"))
        val street = patchValue(before.street, grouped.forProperty("street"))
        return PatchResult(
            before.copy(id = id.value, street = street.value),
            grouped.unmatchedFailures("Entry") + id.failures + street.failures,
        )
    }
}

private fun at(vararg segments: Segment) = FieldPath(segments.toList())

class GroupingSpec :
    FunSpec({

        test("changes are grouped under the property their path starts with") {
            val grouped = groupByProperty(
                listOf(
                    ValueChanged(at(Segment.Field("name")), "Ada", "Grace"),
                    ValueChanged(at(Segment.Field("address"), Segment.Field("street")), "X", "Y"),
                ),
                setOf("name", "address"),
            )

            grouped.forProperty("name") shouldHaveSize 1
            grouped.forProperty("address") shouldHaveSize 1
            grouped.unmatched.shouldBeEmpty()
        }

        test("grouping strips the property's own segment so the nested differ sees a relative path") {
            val grouped = groupByProperty(
                listOf(ValueChanged(at(Segment.Field("address"), Segment.Field("street")), "X", "Y")),
                setOf("address"),
            )

            grouped.forProperty("address").single().path.toString() shouldBe "street"
        }

        test("a change matching no compared property is unmatched and reported") {
            val change = ValueChanged(at(Segment.Field("unknown")), "a", "b")

            val grouped = groupByProperty(listOf(change), setOf("name"))

            grouped.unmatched shouldContainExactly listOf(change)
            grouped.unmatchedFailures("Person").single().reason shouldContain "no compared property"
        }

        test("a result with no failures reports itself clean") {
            PatchResult("x").isClean shouldBe true
            PatchResult("x", listOf(PatchFailure(ValueChanged(at(), 1, 2), "why"))).isClean shouldBe false
        }
    })

class PatchValueSpec :
    FunSpec({

        test("a value change sets the property") {
            patchValue("Ada", listOf(ValueChanged(at(), "Ada", "Grace"))).value shouldBe "Grace"
        }

        test("a null after value is applied as null") {
            patchValue<String?>("Ada", listOf(ValueChanged(at(), "Ada", null))).value shouldBe null
        }

        test("no changes leaves the source value") {
            patchValue("Ada", emptyList()).value shouldBe "Ada"
        }
    })

class PatchNestedSpec :
    FunSpec({

        test("a nested change is delegated and rebuilds the nested value") {
            val patched = patchNested(
                Entry("A1", "Rue X"),
                listOf(ValueChanged(at(Segment.Field("street")), "Rue X", "Rue Y")),
                EntryPatcher,
            )

            patched.value shouldBe Entry("A1", "Rue Y")
            patched.failures.shouldBeEmpty()
        }

        test("a nullable nested property is set wholesale by a change at the property itself") {
            val patched = patchNestedNullable(
                Entry("A1", "Rue X"),
                listOf(ValueChanged(at(), Entry("A1", "Rue X"), null)),
                EntryPatcher,
            )

            patched.value shouldBe null
        }

        test("a change beneath a null nullable property is reported rather than lost") {
            val patched = patchNestedNullable(
                null,
                listOf(ValueChanged(at(Segment.Field("street")), "X", "Y")),
                EntryPatcher,
            )

            patched.value shouldBe null
            patched.failures shouldHaveSize 1
        }
    })

class PatchKeyedListSpec :
    FunSpec({

        val a1 = Entry("A1", "Rue 1")
        val a2 = Entry("A2", "Rue 2")
        val a3 = Entry("A3", "Rue 3")

        fun key(id: String) = Segment.Key("id", id)

        test("a modification, an addition, a removal and a move apply together") {
            val patched = patchKeyedList(
                listOf(a1, a2),
                listOf(
                    ValueChanged(at(key("A2"), Segment.Field("street")), "Rue 2", "Rue X"),
                    Added(at(key("A3")), a3),
                    Removed(at(key("A1")), a1),
                    Moved(at(key("A2")), 1, 0),
                ),
                EntryPatcher,
            ) { it.id }

            patched.value shouldContainExactly listOf(a2.copy(street = "Rue X"), a3)
            patched.failures.shouldBeEmpty()
        }

        test("a move places the element at its target index") {
            val patched = patchKeyedList(
                listOf(a1, a2),
                listOf(Moved(at(key("A2")), 1, 0), Moved(at(key("A1")), 0, 1)),
                EntryPatcher,
            ) { it.id }

            patched.value shouldContainExactly listOf(a2, a1)
        }

        test("no changes leaves the list as it was") {
            patchKeyedList(listOf(a1, a2), emptyList(), EntryPatcher) { it.id }
                .value shouldContainExactly listOf(a1, a2)
        }

        test("a change for a key that is not present is reported") {
            val patched = patchKeyedList(
                listOf(a1),
                listOf(ValueChanged(at(key("ZZ"), Segment.Field("street")), "a", "b")),
                EntryPatcher,
            ) { it.id }

            patched.failures shouldHaveSize 1
            patched.value shouldContainExactly listOf(a1)
        }

        test("a source holding two elements with one key is rejected, not rebuilt as one of them twice") {
            val shadowed = Entry("A1", "Rue 9")

            shouldThrow<IllegalArgumentException> {
                patchKeyedList(listOf(a1, shadowed), emptyList(), EntryPatcher, "entries", "id") { it.id }
            }.message shouldBe
                "entries is keyed by id, but two elements share the key A1. " +
                "A keyed element must be uniquely identified; entries[id=A1] cannot name one of them."
        }

        test("a hand-written patcher omitting the names still rejects, describing the list generically") {
            val shadowed = Entry("A1", "Rue 9")

            shouldThrow<IllegalArgumentException> {
                patchKeyedList(listOf(a1, shadowed), emptyList(), EntryPatcher) { it.id }
            }.message.shouldContain("share the key A1")
        }

        test("a nested value no change addresses is never rebuilt, so a duplicate inside it is never reached") {
            val neverCalled = object : Patcher<Entry> {
                override fun apply(before: Entry, changes: List<Change>): PatchResult<Entry> =
                    error("reconstruction should not have been attempted")
            }
            val untouched = Entry("A1", "Rue 1")

            patchNested(untouched, emptyList(), neverCalled).value shouldBe untouched
        }

        test("a round trip over a list with a repeated key refuses rather than reproducing the target") {
            val shadowed = Entry("A1", "Rue 9")

            shouldThrow<IllegalArgumentException> {
                buildList {
                    compareKeyedList("entries", "id", listOf(a1, shadowed), listOf(a1), EntryPatcher) { it.id }
                }
            }
        }
    })

class PatchPositionalListSpec :
    FunSpec({

        fun index(i: Int) = Segment.Index(i)

        test("a changed element is replaced at its index") {
            val patched = patchPositionalList(
                listOf("a", "b"),
                listOf(ValueChanged(at(index(1)), "b", "c")),
                patcher = null,
            )

            patched.value shouldContainExactly listOf("a", "c")
        }

        test("a trailing addition lengthens the list") {
            val patched = patchPositionalList(listOf("a"), listOf(Added(at(index(1)), "b")), null)

            patched.value shouldContainExactly listOf("a", "b")
        }

        test("a trailing removal shortens the list") {
            val patched = patchPositionalList(listOf("a", "b"), listOf(Removed(at(index(1)), "b")), null)

            patched.value shouldContainExactly listOf("a")
        }
    })

class PatchSetSpec :
    FunSpec({

        test("removals and additions rebuild the membership") {
            val patched = patchSet(
                setOf("a", "b"),
                listOf(Removed(at(), "a"), Added(at(), "c")),
            )

            patched.value shouldBe setOf("b", "c")
            patched.failures.shouldBeEmpty()
        }

        test("a change beneath a set element is reported, since a set element has no identity") {
            val patched = patchSet(setOf("a"), listOf(ValueChanged(at(Segment.Field("x")), 1, 2)))

            patched.failures shouldHaveSize 1
        }
    })

class PatchMapSpec :
    FunSpec({

        fun key(k: Any?) = Segment.Key("key", k)

        test("a changed, an added and a removed entry apply together") {
            val patched = patchMap(
                mapOf("eur" to "1.0", "gbp" to "2.0"),
                listOf(
                    ValueChanged(at(key("eur")), "1.0", "1.1"),
                    Added(at(key("usd")), "3.0"),
                    Removed(at(key("gbp")), "2.0"),
                ),
                patcher = null,
            )

            patched.value shouldBe mapOf("eur" to "1.1", "usd" to "3.0")
            patched.failures.shouldBeEmpty()
        }

        test("an entry whose key is not a string is applied from the key segment") {
            val patched = patchMap(
                mapOf(1 to "one"),
                listOf(Added(at(key(2)), "two"), ValueChanged(at(key(1)), "one", "uno")),
                patcher = null,
            )

            patched.value shouldBe mapOf(1 to "uno", 2 to "two")
        }

        test("a map descends into an annotated value type") {
            val patched = patchMap(
                mapOf("a" to Entry("A1", "Rue X")),
                listOf(ValueChanged(at(key("a"), Segment.Field("street")), "Rue X", "Rue Y")),
                EntryPatcher,
            )

            patched.value shouldBe mapOf("a" to Entry("A1", "Rue Y"))
        }
    })

class PatchFailureReasonSpec :
    FunSpec({

        test("an unpatchable property names itself and says its differ cannot patch") {
            val failure = unpatchable("x", listOf(ValueChanged(at(), 1, 2)), "total").failures.single()

            failure.reason shouldContain "total"
            failure.reason shouldContain "cannot patch"
        }

        test("a non-constructor property says why it cannot be reconstructed") {
            val failure = notConstructorProperty("x", listOf(ValueChanged(at(), 1, 2)), "derived")
                .failures.single()

            failure.reason shouldContain "derived"
            failure.reason shouldContain "only constructor properties"
        }
    })
