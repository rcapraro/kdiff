package io.github.kdiff.integration

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * A build that reuses previous output agrees with a build from nothing, and regenerates no more than it
 * has to.
 *
 * Both halves need two builds with an edit between them, which is why they live here rather than in the
 * processor's compile-testing specs: kctfork compiles once, so it can state what the processor emits
 * but never what a *second* build does with what the first one left.
 */
class IncrementalGenerationSpec :
    FunSpec({

        test("editing a value type regenerates the differ that compared it, and refuses it") {
            val consumer = ConsumerProject(tempdir())
            consumer.write()
            consumer.main("Product.kt", MODEL_WITH_VALUE_TYPE)
            consumer.main("Sku.kt", VALUE_TYPE)

            consumer.build("compileKotlin")
            consumer.generated("Product")!!.readText() shouldContain "compareValue(\"sku\""

            // Only the value type's own file changes. The annotated class that reads it is untouched,
            // so a differ kept from the first build would compile and be wrong.
            consumer.main("Sku.kt", VALUE_TYPE_DEMOTED)
            val second = consumer.buildAndFail("compileKotlin")

            second.output shouldContain "kdiff cannot compare sku of type consumer.Sku"
        }

        // The other direction, and the half `docs/architecture.md` claims: every generated file
        // declares the file it came from, so one annotated class changing does not regenerate the rest.
        // Asserted on modification time rather than on content, because an aggregating declaration
        // would regenerate the unrelated file with byte-identical content and a content check would
        // not notice.
        test("an unrelated annotated class is not regenerated") {
            val consumer = ConsumerProject(tempdir())
            consumer.write()
            consumer.main("Model.kt", MODEL)
            consumer.main("Customer.kt", UNRELATED)

            consumer.build("kspKotlin")
            val editedBefore = consumer.generated("Order")!!.lastModified()
            val untouchedBefore = consumer.generated("Customer")!!.lastModified()

            val edited = MODEL.replace("val reference: String", "val reference: String, val note: String?")
            consumer.main("Model.kt", edited)
            consumer.build("kspKotlin")

            withClue("the edited class's differ") {
                consumer.generated("Order")!!.lastModified() shouldNotBe editedBefore
                consumer.generated("Order")!!.readText() shouldContain "note"
            }
            withClue("the unrelated class's differ") {
                consumer.generated("Customer")!!.lastModified() shouldBe untouchedBefore
            }
        }
    })
