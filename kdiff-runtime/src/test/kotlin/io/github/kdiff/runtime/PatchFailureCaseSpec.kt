package io.github.kdiff.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private val at = FieldPath.of("city")
private val change: Change = ValueChanged(at, "Paris", "Nice")

private fun aFailure(reason: PatchFailure.Reason) = PatchFailure(change, reason)

class PatchFailureCaseSpec :
    FunSpec({

        // Each case must render the sentence the runtime produced before reasons were typed, so a
        // failure that reaches a log reads exactly as it always did.
        context("a reason renders the sentence it replaced") {
            val rendered = mapOf<PatchFailure.Reason, String>(
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

            rendered.forEach { (reason, sentence) ->
                test("${reason::class.simpleName} renders as \"$sentence\"") {
                    aFailure(reason).toString() shouldBe "city: $sentence"
                }
            }

            // Enumerating the cases to check none is missing would take `sealedSubclasses`, and so
            // `kotlin-reflect` — the one dependency this module refuses. The guarantee lives in the
            // `when` inside `describe` instead: a new case fails to compile there.
            test("the rendering table covers every case the runtime can produce") {
                rendered.size shouldBe 14
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
    })
