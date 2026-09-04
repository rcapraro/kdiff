package io.github.kdiff.runtime

import kotlin.reflect.KProperty1

/**
 * Builds a [Differ] for a type whose source cannot be annotated.
 *
 * The result is indistinguishable from a generated differ to anything that consumes it, so the two
 * compose in either direction. Hold it in an object to name it from `@DiffWith`:
 *
 * ```
 * object MoneyDiffer : Differ<Money> by differ({
 *     field(Money::amount)
 *     field(Money::currency)
 * })
 * ```
 *
 * Property references are stdlib: reading `name` and calling `get` needs no `kotlin-reflect`.
 */
public fun <T> differ(block: DifferBuilder<T>.() -> Unit): Differ<T> =
    DifferBuilder<T>().apply(block).build()

public class DifferBuilder<T> internal constructor() {
    private val comparisons = mutableListOf<(MutableList<Change>, T, T) -> Unit>()

    /** Compares [property] by value. */
    public fun <V> field(property: KProperty1<T, V>) {
        comparisons += { changes, before, after ->
            changes.compareValue(property.name, property.get(before), property.get(after))
        }
    }

    /** Compares [property] by delegating to [differ], lifting its paths under the property's name. */
    public fun <V> nested(property: KProperty1<T, V>, differ: Differ<V>) {
        comparisons += { changes, before, after ->
            changes.compareNested(property.name, property.get(before), property.get(after), differ)
        }
    }

    internal fun build(): Differ<T> = object : Differ<T> {
        override fun diff(before: T, after: T): Diff = Diff(
            buildList { comparisons.forEach { it(this, before, after) } },
        )
    }
}

/**
 * Builds a [TrackScope] for a type whose source cannot be annotated.
 *
 * The result is indistinguishable from the scope a `@Trackable` class declares, so a tracker behaves
 * the same either way and a type carrying no kdiff annotation can be both compared and tracked:
 *
 * ```
 * val scope = trackScope<Order> {
 *     field(Order::reference)
 *     under(Order::billing)
 * }
 * ```
 *
 * Naming no property tracks every compared property, at whatever [TrackScopeBuilder.depth] says.
 *
 * Property references are stdlib: reading `name` needs no `kotlin-reflect`, exactly as in [differ].
 */
public fun <T> trackScope(block: TrackScopeBuilder<T>.() -> Unit): TrackScope<T> =
    TrackScopeBuilder<T>().apply(block).build()

public class TrackScopeBuilder<T> internal constructor() {
    private val fields = mutableListOf<TrackedField>()

    private var statedDepth: Int? = null

    /**
     * The depth applied when this scope names no property, and so tracks every compared property.
     *
     * A property named through [field] or [under] carries its own depth, which this does not touch.
     * Stating a depth here does override the depths a `@Trackable` class declared, which is how a
     * caller reaches deeper than the type's author chose to.
     */
    public var depth: Int
        get() = statedDepth ?: UNLIMITED_DEPTH
        set(value) {
            require(value == UNLIMITED_DEPTH || value >= 1) {
                "depth must be at least 1, or UNLIMITED_DEPTH; was $value"
            }
            statedDepth = value
        }

    /** Tracks [property] itself, and nothing nested beneath it. */
    public fun field(property: KProperty1<T, *>) {
        fields += TrackedField(property.name, depth = 1)
    }

    /** Tracks [property] to [depth] property steps beneath the tracked object. */
    public fun field(property: KProperty1<T, *>, depth: Int) {
        fields += TrackedField(property.name, depth)
    }

    /** Tracks [property] and everything nested beneath it, however deep. */
    public fun under(property: KProperty1<T, *>) {
        fields += TrackedField(property.name, UNLIMITED_DEPTH)
    }

    internal fun build(): TrackScope<T> =
        TrackScope(fields.takeIf { it.isNotEmpty() }?.toList(), statedDepth)
}
