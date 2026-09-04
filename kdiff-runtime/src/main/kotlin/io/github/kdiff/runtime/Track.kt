package io.github.kdiff.runtime

import kotlin.reflect.KProperty1

/**
 * Reports how an evolving value differs from the last one it was shown.
 *
 * A tracker holds a baseline. [update] compares it against a new instance, reports the changes its
 * scope selects, and adopts that instance as the new baseline — so a caller feeds instances in and
 * hears only about what they asked for.
 *
 * The report is an ordinary [Diff], which is what [Patcher] consumes: a tracker's output can be
 * applied to its baseline with no conversion. An unrestricted scope reproduces the instance the
 * tracker was shown; a narrowed one propagates the tracked properties and leaves the rest alone.
 *
 * Not thread-safe. [update] reads the baseline, compares, dispatches and writes the baseline, and
 * making that atomic would mean holding a lock across the callbacks. Confine a tracker to one thread;
 * a [TrackScope] is immutable and may be shared freely.
 */
public class Tracker<T> internal constructor(
    private val differ: Differ<T>,
    private val scope: ResolvedScope,
    private val fieldListeners: List<(FieldPath, Any?, Any?) -> Unit>,
    private val changeListeners: List<(T, T, List<Change>) -> Unit>,
    baseline: T,
) {
    /** The instance every following [update] is compared against. */
    public var current: T
        private set

    init {
        current = baseline
    }

    /**
     * Compares [next] against the baseline, reports what the scope selects, and adopts [next].
     *
     * Returns the selected changes. Every registered per-field listener fires once per change, in the
     * order the differ found them; every batched listener fires once, and only when something matched.
     */
    public fun update(next: T): Diff {
        val previous = current
        val selected = differ.diff(previous, next).changes.filter(scope::selects)
        current = next

        if (selected.isEmpty()) return Diff(selected)

        selected.forEach { change ->
            val (before, after) = change.sides()
            fieldListeners.forEach { it(change.path, before, after) }
        }
        changeListeners.forEach { it(previous, next, selected) }

        return Diff(selected)
    }

    /** Adopts [baseline] without comparing and without reporting, for a caller resynchronising. */
    public fun reset(baseline: T) {
        current = baseline
    }
}

/**
 * Builds a [Tracker] over [initial], comparing with [differ].
 *
 * Naming no property tracks whatever the type declared with `@Trackable`, or every compared property
 * when it declared nothing:
 *
 * ```
 * val tracker = tracker(OrderDiffer, order) {
 *     field(Order::reference)
 *     under(Order::billing)
 *     onFieldChange { path, before, after -> log("$path: $before -> $after") }
 * }
 * ```
 */
public fun <T> tracker(
    differ: Differ<T>,
    initial: T,
    block: TrackerBuilder<T>.() -> Unit = {},
): Tracker<T> = TrackerBuilder<T>().apply(block).build(differ, initial)

/**
 * Builds a [Tracker] over [initial] from a scope prepared elsewhere, whether by `trackScope { }` or
 * read off a generated declaration, so one scope can be reused across trackers.
 */
public fun <T> tracker(
    differ: Differ<T>,
    initial: T,
    scope: TrackScope<T>,
    block: TrackerBuilder<T>.() -> Unit = {},
): Tracker<T> = TrackerBuilder<T>().apply { scope(scope) }.apply(block).build(differ, initial)

public class TrackerBuilder<T> internal constructor() {
    private val selectors = TrackScopeBuilder<T>()
    private var prepared: TrackScope<T>? = null
    private val fieldListeners = mutableListOf<(FieldPath, Any?, Any?) -> Unit>()
    private val changeListeners = mutableListOf<(T, T, List<Change>) -> Unit>()

    /** See [TrackScopeBuilder.depth]. */
    public var depth: Int
        get() = selectors.depth
        set(value) {
            selectors.depth = value
        }

    /** Tracks [property] itself, and nothing nested beneath it. */
    public fun field(property: KProperty1<T, *>): Unit = selectors.field(property)

    /** Tracks [property] to [depth] property steps beneath the tracked object. */
    public fun field(property: KProperty1<T, *>, depth: Int): Unit = selectors.field(property, depth)

    /** Tracks [property] and everything nested beneath it, however deep. */
    public fun under(property: KProperty1<T, *>): Unit = selectors.under(property)

    /**
     * Tracks what [scope] names, unless a property is named here as well — naming one is the more
     * local statement and wins outright. A [depth] stated here applies to what [scope] names.
     */
    public fun scope(scope: TrackScope<T>) {
        prepared = scope
    }

    /** Reports each selected change on its own, with its path and its two sides. */
    public fun onFieldChange(listener: (path: FieldPath, before: Any?, after: Any?) -> Unit) {
        fieldListeners += listener
    }

    /** Reports one update as a whole, with both instances and every selected change. */
    public fun onChange(listener: (before: T, after: T, changes: List<Change>) -> Unit) {
        changeListeners += listener
    }

    internal fun build(differ: Differ<T>, initial: T): Tracker<T> {
        val named = selectors.build()
        val given = prepared

        // A stated depth must narrow or widen the prepared scope, never discard what it names:
        // dropping its properties would fall back to tracking everything, silently reporting far
        // more than the caller asked for.
        val scope = when {
            named.fields != null -> named
            given != null -> given.atDepth(named.depth)
            else -> named
        }

        return Tracker(
            differ = differ,
            scope = scope.resolveAgainst(differ),
            fieldListeners = fieldListeners.toList(),
            changeListeners = changeListeners.toList(),
            baseline = initial,
        )
    }
}
