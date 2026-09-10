package io.github.kdiff.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private val at = FieldPath.of("city")
private val change: Change = ValueChanged(at, "Paris", "Nice")

private fun aFailure(reason: PatchFailure.Reason) = PatchFailure(change, reason)

/**
 * Every case the runtime can produce, with the sentence it renders as.
 *
 * At the top of the file rather than inside one context: two contexts read it, and the second one
 * needs the cases it does *not* name.
 */
private val rendered = mapOf<PatchFailure.Reason, String>(
    PatchFailure.Reason.UnknownProperty("Address") to "Address has no compared property at this path",
    PatchFailure.Reason.UnpatchableProperty("total") to
        "total is compared by a differ that cannot patch",
    PatchFailure.Reason.NotConstructorProperty("derived") to
        "derived: only constructor properties can be reconstructed",
    PatchFailure.Reason.NotApplicableToValue to "not applicable to a value property",
    PatchFailure.Reason.NotApplicableToKeyedList to "not applicable to a keyed list",
    PatchFailure.Reason.NotApplicableToPositionalList to "not applicable to a positional list",
    PatchFailure.Reason.NotApplicableToMap to "not applicable to a map",
    PatchFailure.Reason.NothingBeneathNull to "nothing to patch beneath a null property",
    PatchFailure.Reason.NoElementForKey to "no element with this key to patch",
    PatchFailure.Reason.NoElementAtIndex to "no element at this index to patch",
    PatchFailure.Reason.NoEntryForKey to "no entry with this key to patch",
    PatchFailure.Reason.ElementComparedAsValue to "element is compared as a value",
    PatchFailure.Reason.EntryComparedAsValue to "entry value is compared as a value",
    PatchFailure.Reason.SetElementNotModifiable to "a set element cannot be modified in place",
)

class PatchFailureCaseSpec :
    FunSpec({

        // Each case must render the sentence the runtime produced before reasons were typed, so a
        // failure that reaches a log reads exactly as it always did.
        context("a reason renders the sentence it replaced") {
            rendered.forEach { (reason, sentence) ->
                test("${reason::class.simpleName} renders as \"$sentence\"") {
                    aFailure(reason).toString() shouldBe "city: $sentence"
                }
            }

            // Enumerating the cases to check none is missing would take `sealedSubclasses`, and so
            // `kotlin-reflect` — the one dependency this module refuses. The guarantee lives in the
            // `when` inside `describe` instead: a new case fails to compile there.
            //
            // So this count does not catch a case declared later — the table is a literal, and a
            // fifteenth case leaves it at fourteen and this test green. What it records is that the
            // table was complete when it was written, so that raising the number is a deliberate edit
            // by whoever adds the case rather than a silent one.
            test("the rendering table covered every case the runtime could produce when it was written") {
                rendered.size shouldBe 14
            }
        }

        // A caller branches on the reasons it acts on and says what it does with the rest, because a
        // minor version may declare a further case. This is that caller, shown handling two and
        // sending every other case the table holds to its fallback. It cannot demonstrate a case that
        // does not exist yet — what it demonstrates is that the shape a caller is told to write does
        // route an unnamed reason somewhere rather than dropping it.
        context("a reason a caller does not name reaches its catch-all") {
            fun act(failure: PatchFailure): String = when (failure.reason) {
                is PatchFailure.Reason.NoElementForKey -> "reinstated"
                is PatchFailure.Reason.NothingBeneathNull -> "skipped"
                else -> "logged"
            }

            test("a named reason reaches its own branch") {
                act(aFailure(PatchFailure.Reason.NoElementForKey)) shouldBe "reinstated"
                act(aFailure(PatchFailure.Reason.NothingBeneathNull)) shouldBe "skipped"
            }

            test("every other declared reason reaches the catch-all") {
                val named = setOf<PatchFailure.Reason>(
                    PatchFailure.Reason.NoElementForKey,
                    PatchFailure.Reason.NothingBeneathNull,
                )

                (rendered.keys - named).forEach { reason ->
                    act(aFailure(reason)) shouldBe "logged"
                }
            }
        }

        context("a reason is handled by case, not by message") {
            test("a case carries the property it concerns") {
                val reason: PatchFailure.Reason = PatchFailure.Reason.NotConstructorProperty("derived")

                when (reason) {
                    is PatchFailure.Reason.NotConstructorProperty -> reason.property shouldBe "derived"
                    else -> error("matched the wrong case")
                }
            }

            test("the type that has no such property is readable off the reason") {
                val reason: PatchFailure.Reason = PatchFailure.Reason.UnknownProperty("Order")

                (reason as PatchFailure.Reason.UnknownProperty).type shouldBe "Order"
            }
        }

        context("a caller can require a patch outright") {
            test("a clean result yields its value") {
                PatchResult("rebuilt").getOrThrow() shouldBe "rebuilt"
            }

            test("a partial result raises instead, carrying every failure") {
                val failures = listOf(
                    aFailure(PatchFailure.Reason.NotApplicableToValue),
                    aFailure(PatchFailure.Reason.NoElementForKey),
                )

                val raised = shouldThrow<PatchFailedException> { PatchResult("partial", failures).getOrThrow() }

                raised.failures shouldContainExactly failures
                raised.message.shouldContain("2 of the changes could not be applied")
            }

            test("requiring the value agrees with reading it when every change applied") {
                val result = PatchResult("rebuilt")

                result.getOrThrow() shouldBe result.value
            }
        }

        // A singleton subclass of a sealed type has no property a change could name, so applying to one
        // returns it and reports whatever it was given.
        context("applying to a sealed singleton") {
            test("an empty change list returns the singleton with no failures") {
                val result = patchSingleton("Unpaid", emptyList(), "Unpaid")

                result.value shouldBe "Unpaid"
                result.isClean shouldBe true
            }

            test("a foreign change is reported as addressing a property the type does not have") {
                val result = patchSingleton("Unpaid", listOf(change), "Unpaid")

                result.value shouldBe "Unpaid"
                result.failures shouldContainExactly
                    listOf(PatchFailure(change, PatchFailure.Reason.UnknownProperty("Unpaid")))
                result.failures.single().toString() shouldBe "city: Unpaid has no compared property at this path"
            }
        }
    })
