package io.github.kdiff.runtime

import kotlin.reflect.KClass
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
 * Every comparison `@Diffable` can declare has a call here, reporting the same changes at the same
 * paths — so a model can be described by hand or by annotation without any consumer noticing.
 *
 * Property references are stdlib: reading `name` and calling `get` needs no `kotlin-reflect`.
 */
public fun <T> differ(block: DifferBuilder<T>.() -> Unit): Differ<T> = DifferBuilder<T>().apply(block).build()

public class DifferBuilder<T> internal constructor() {
    private val comparisons = mutableListOf<(MutableList<Change>, T, T) -> Unit>()
    private val subtypes = mutableListOf<Subtype<T, *>>()

    /**
     * Compares [property] by value.
     *
     * A property named nowhere in the builder is not compared, which is what `@DiffIgnore` states
     * for an annotated class.
     */
    public fun field(property: KProperty1<T, *>) {
        comparisons += { changes, before, after ->
            changes.compareValue(property.name, property.get(before), property.get(after))
        }
    }

    /**
     * Compares [property] by delegating to [differ], lifting its paths under the property's name.
     *
     * A null on either side is a value change at the property, never an addition or a removal: the
     * property exists on both sides or on neither. One call serves a nullable property and a non-null
     * one, because a property reference is covariant in the value it reads.
     */
    public fun <V : Any> nested(property: KProperty1<T, V?>, differ: Differ<V>) {
        comparisons += { changes, before, after ->
            changes.compareNestedNullable(property.name, property.get(before), property.get(after), differ)
        }
    }

    /**
     * Compares [property] index by index, descending into elements with [differ] when one is given.
     *
     * A move is never reported: with no key there is nothing to recognise a moved element by. Name a
     * key with [keyedList] to get moves.
     */
    public fun <E : Any> list(property: KProperty1<T, List<E>>, differ: Differ<E>? = null) {
        comparisons += { changes, before, after ->
            changes.comparePositionalList(property.name, property.get(before), property.get(after), differ)
        }
    }

    /**
     * Compares [property] by matching elements on [key] rather than on position, so a reordered
     * element reports as moved instead of as a removal and an addition.
     *
     * [key] supplies both halves the comparison needs — the name a path segment carries and the value
     * it identifies an element by — so the two cannot get out of step. It is the counterpart of
     * `@DiffKey` on the element type.
     */
    public fun <E : Any, K : Any> keyedList(
        property: KProperty1<T, List<E>>,
        key: KProperty1<E, K>,
        differ: Differ<E>,
    ) {
        comparisons += { changes, before, after ->
            changes.compareKeyedList(
                property.name,
                key.name,
                property.get(before),
                property.get(after),
                differ,
                key::get,
            )
        }
    }

    /**
     * Compares [property] as unordered membership.
     *
     * Never reports a move or a modified element: set elements have no stable identity, so a modified
     * element is indistinguishable from one removed and another added.
     */
    public fun set(property: KProperty1<T, Set<*>>) {
        comparisons += { changes, before, after ->
            changes.compareSet(property.name, property.get(before), property.get(after))
        }
    }

    /** Compares [property] by entry key, descending into values with [values] when one is given. */
    public fun <K : Any, V : Any> map(property: KProperty1<T, Map<K, V>>, values: Differ<V>? = null) {
        comparisons += { changes, before, after ->
            changes.compareMap(property.name, property.get(before), property.get(after), values)
        }
    }

    /**
     * Compares two instances of [type] by delegating to [differ], the way an annotated sealed type
     * dispatches on the runtime subclass.
     *
     * Declaring any subtype changes how this differ compares. Two instances of one declared subtype
     * are compared by that subtype's differ alone; two instances of *different* types report one
     * [TypeChanged] at the root and then compare the properties named here — the parent's own
     * properties being the only ones comparable across a swap.
     *
     * Two instances of one *undeclared* subtype are compared by those same properties, and no type
     * change is reported: nothing about the type changed. A generated differ enumerates every
     * subclass and so never meets that case; a hand-written one can, by omitting a subtype.
     */
    public fun <S : T & Any> subtype(type: KClass<S>, differ: Differ<S>) {
        subtypes += Subtype(type, differ)
    }

    internal fun build(): Differ<T> {
        val comparisons = comparisons.toList()
        val subtypes = subtypes.toList()

        return object : Differ<T> {
            override fun diff(before: T, after: T): Diff = Diff(
                buildList {
                    if (subtypes.isNotEmpty()) {
                        val shared = subtypes.firstOrNull { it.holdsBoth(before, after) }
                        if (shared != null) {
                            addAll(shared.diff(before, after))
                            return@buildList
                        }
                        if (typeOf(before) != typeOf(after)) add(typeChange(before, after))
                    }
                    comparisons.forEach { it(this, before, after) }
                },
            )
        }
    }

    private fun typeChange(before: T, after: T): TypeChanged = TypeChanged(
        FieldPath.ROOT,
        typeOf(before)?.simpleName.orEmpty(),
        typeOf(after)?.simpleName.orEmpty(),
        before,
        after,
    )

    private fun typeOf(value: T): KClass<*>? = (value as Any?)?.let { it::class }
}

/** One `subtype` declaration, holding the cast its own type argument makes safe. */
private class Subtype<T, S : Any>(private val type: KClass<S>, private val differ: Differ<S>) {
    fun holdsBoth(before: T, after: T): Boolean = type.isInstance(before) && type.isInstance(after)

    // Only reached through holdsBoth, which is exactly the test this cast needs.
    @Suppress("UNCHECKED_CAST")
    fun diff(before: T, after: T): List<Change> = differ.diff(before as S, after as S).changes
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
    private val excluded = mutableSetOf<String>()

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

    /**
     * Tracks every compared property except [property], the hand-written counterpart of
     * `@TrackIgnore`.
     *
     * Nothing beneath an excluded property is reported either, at any depth. Excluding a property the
     * differ does not compare excludes nothing: it can never appear in a change.
     */
    public fun except(property: KProperty1<T, *>) {
        excluded += property.name
    }

    internal fun build(): TrackScope<T> {
        // The two say opposite things about every property named in neither, and a precedence rule
        // between them would decide silently which one widens the scope.
        require(fields.isEmpty() || excluded.isEmpty()) {
            "a scope names the properties it tracks or the properties it excludes, never both"
        }

        return TrackScope(fields.takeIf { it.isNotEmpty() }?.toList(), statedDepth, excluded.toSet())
    }
}
