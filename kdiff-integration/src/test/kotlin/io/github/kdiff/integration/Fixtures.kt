package io.github.kdiff.integration

/**
 * The smallest model that still reaches the shapes a release has to get right: a nested `@Diffable`,
 * a keyed list of it, and a value property.
 */
internal const val MODEL = """
    package consumer

    import io.github.kdiff.annotations.DiffKey
    import io.github.kdiff.annotations.Diffable

    @Diffable
    data class Address(@DiffKey val id: String, val city: String)

    @Diffable
    data class Order(val reference: String, val addresses: List<Address>)
"""

/**
 * A model whose comparison decision is taken from a *different* file: `Sku` is an inline `value class`,
 * which is why `sku` compares by equality, and that declaration lives in [VALUE_TYPE].
 */
internal const val MODEL_WITH_VALUE_TYPE = """
    package consumer

    import io.github.kdiff.annotations.Diffable

    @Diffable
    data class Product(val name: String, val sku: Sku)
"""

/** The value type [MODEL_WITH_VALUE_TYPE] reads, in its own file so it can be edited alone. */
internal const val VALUE_TYPE = """
    package consumer

    @JvmInline
    value class Sku(val code: String)
"""

/** The same type redeclared as something kdiff cannot compare, leaving its reader untouched. */
internal const val VALUE_TYPE_DEMOTED = """
    package consumer

    data class Sku(val code: String, val issued: String)
"""

/** A second annotated class sharing no type with [MODEL], for the regeneration narrowness check. */
internal const val UNRELATED = """
    package consumer

    import io.github.kdiff.annotations.Diffable

    @Diffable
    data class Customer(val name: String)
"""

/**
 * Compares, applies, and checks both — run inside the consumer build, so reaching the end means the
 * generated code executed against the published runtime.
 *
 * `check` from the standard library rather than a test framework: a consumer needs the three
 * coordinates and nothing else, and that is exactly what this build is here to demonstrate.
 */
internal const val USAGE = """
    package consumer

    import io.github.kdiff.runtime.ValueChanged

    fun main() {
        val before = Order("R-1", listOf(Address("A1", "Paris")))
        val after = Order("R-2", listOf(Address("A1", "Nice")))

        val diff = OrderDiffer.diff(before, after)
        val paths = diff.changes.map { it.path.toString() }

        check(paths == listOf("reference", "addresses[id=A1].city")) { "unexpected paths: ${'$'}paths" }
        check(diff.changes.all { it is ValueChanged }) { "unexpected change kinds: ${'$'}{diff.changes}" }

        val result = OrderDiffer.apply(before, diff.changes)

        check(result.value == after) { "apply did not rebuild the target: ${'$'}{result.value}" }
        check(result.isClean) { "apply reported failures: ${'$'}{result.failures}" }
    }
"""
