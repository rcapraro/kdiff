package io.github.kdiff.runtime

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
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

        if (fieldListeners.isNotEmpty()) {
            selected.forEach { change ->
                change.withSides { before, after ->
                    fieldListeners.forEach { it(change.path, before, after) }
                }
            }
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
@OptIn(ExperimentalContracts::class)
public inline fun <T> tracker(differ: Differ<T>, initial: T, block: TrackerBuilder<T>.() -> Unit = {}): Tracker<T> {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return TrackerBuilder<T>().apply(block).build(differ, initial)
}

/**
 * Builds a [Tracker] over [initial] from a scope prepared elsewhere, whether by `trackScope { }` or
 * read off a generated declaration, so one scope can be reused across trackers.
 */
@OptIn(ExperimentalContracts::class)
public inline fun <T> tracker(
    differ: Differ<T>,
    initial: T,
    scope: TrackScope<T>,
    block: TrackerBuilder<T>.() -> Unit = {},
): Tracker<T> {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return TrackerBuilder<T>().apply { scope(scope) }.apply(block).build(differ, initial)
}

/**
 * Compares [before] against [after] and reports only what [scope] selects, holding no baseline.
 *
 * The tracked view of one transition, for a caller that already holds both instances — a command
 * handler comparing current state against the state a command asks for. The result is what a
 * [Tracker] carrying the same scope would report for the same pair; a tracker is the right tool for
 * an instance that keeps evolving, and this one for a pair that does not.
 *
 * Passing no scope uses the scope the differ's type declared with `@Trackable`, and reports every
 * compared property when it declared none.
 */
public fun <T> Differ<T>.trackedDiff(before: T, after: T, scope: TrackScope<T>? = null): Diff {
    val resolved = (scope ?: TrackScope<T>(fields = null, depth = null)).resolveAgainst(this)
    return Diff(diff(before, after).changes.filter(resolved::selects))
}

@KdiffDsl
// Private primary, no-argument `@PublishedApi` secondary — see `TrackScopeBuilder` for why the
// delegate must not appear in a published constructor signature.
public class TrackerBuilder<T> private constructor(private val selectors: Selectors<T>) :
    ScopeDeclaration<T> by selectors {
    @PublishedApi
    internal constructor() : this(Selectors())

    private var prepared: TrackScope<T>? = null
    private val fieldListeners = mutableListOf<(FieldPath, Any?, Any?) -> Unit>()
    private val changeListeners = mutableListOf<(T, T, List<Change>) -> Unit>()

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

    @PublishedApi
    internal fun build(differ: Differ<T>, initial: T): Tracker<T> {
        val named = selectors.build()
        val given = prepared

        // A stated depth must narrow or widen the prepared scope, never discard what it names:
        // dropping its properties would fall back to tracking everything, silently reporting far
        // more than the caller asked for. An exclusion named here narrows it the same way.
        val scope = when {
            named.fields != null -> named
            given != null -> given.atDepth(named.depth).without(named.excluded)
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
