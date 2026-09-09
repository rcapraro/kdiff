package io.github.kdiff.runtime

import kotlin.reflect.KProperty1

/**
 * A single difference found between two instances, located at [path].
 *
 * Sealed so the change vocabulary stays closed: every kind of difference kdiff can report is
 * declared here, and callers can handle them exhaustively.
 */
public sealed interface Change {
    public val path: FieldPath

    /**
     * The same change relocated under [segment].
     *
     * Part of the contract rather than a helper: a nested differ reports paths relative to itself,
     * and its caller lifts them. A subtype that could not do this would silently lose the location
     * of everything nested beneath it.
     */
    public fun prefixedWith(segment: Segment): Change
}

/** A property or element compared unequal, holding both sides. */
public data class ValueChanged(override val path: FieldPath, public val before: Any?, public val after: Any?) :
    Change {
    override fun prefixedWith(segment: Segment): ValueChanged = copy(path = path.prefixedWith(segment))
}

/** A collection element or map entry present only in the new instance. */
public data class Added(override val path: FieldPath, public val value: Any?) : Change {
    override fun prefixedWith(segment: Segment): Added = copy(path = path.prefixedWith(segment))
}

/** A collection element or map entry present only in the old instance. */
public data class Removed(override val path: FieldPath, public val value: Any?) : Change {
    override fun prefixedWith(segment: Segment): Removed = copy(path = path.prefixedWith(segment))
}

/**
 * A sealed-typed value that is a different subclass on each side.
 *
 * Carries the values as well as their type names: a type name alone cannot be turned back into an
 * instance, so without them a subclass change could be reported but never replayed.
 */
public data class TypeChanged(
    override val path: FieldPath,
    public val beforeType: String,
    public val afterType: String,
    public val before: Any?,
    public val after: Any?,
) : Change {
    override fun prefixedWith(segment: Segment): TypeChanged = copy(path = path.prefixedWith(segment))
}

/** A keyed collection element that kept its identity but changed position. */
public data class Moved(override val path: FieldPath, public val from: Int, public val to: Int) : Change {
    override fun prefixedWith(segment: Segment): Moved = copy(path = path.prefixedWith(segment))
}

/**
 * The same change relocated under [outer] and then [inner], in one path copy rather than two.
 *
 * The two-segment counterpart of [Change.prefixedWith], for the collection helpers that lift a nested
 * change under its element and then under its property. Internal for the reason the [FieldPath]
 * overload is: one-segment lifting is the contract, this is the shortcut.
 */
internal fun Change.prefixedWith(outer: Segment, inner: Segment): Change = withPath(path.prefixedWith(outer, inner))

/**
 * This change carrying [path] instead of its own.
 *
 * Exhaustive by construction, like `Change.withSides`: a sixth [Change] variant fails to compile here
 * rather than silently keeping the wrong path. Every relocation of a change goes through this, so it
 * is the only place that list has to be kept complete.
 */
internal fun Change.withPath(path: FieldPath): Change = when (this) {
    is ValueChanged -> copy(path = path)
    is Added -> copy(path = path)
    is Removed -> copy(path = path)
    is TypeChanged -> copy(path = path)
    is Moved -> copy(path = path)
}

/**
 * The result of comparing two instances: every [Change] found, in the order the fields were
 * compared.
 *
 * A `Diff` is a value — two results built from the same changes are equal.
 *
 * Not a data class: it is a container with behaviour rather than a record of its properties. `copy` is
 * the constructor spelled longer, destructuring one change list is no read anybody wants, and both
 * would fix the shape of `Diff` forever.
 */
public class Diff(public val changes: List<Change>) : Iterable<Change> {

    /**
     * The changes this diff holds, so the standard library's operators apply to a `Diff` directly.
     *
     * [Iterable] rather than `List`: a `Diff` is a value that equals only another `Diff`, so claiming
     * to be a list while equalling none would mislead, and `subList`, `indexOf` and `listIterator`
     * are twenty members of surface nobody asked for. Iteration, [size] and [isEmpty] are what a
     * caller actually reaches for, and [changes] is still there for anything else.
     */
    override fun iterator(): Iterator<Change> = changes.iterator()

    /** How many changes were found. */
    public val size: Int
        get() = changes.size

    /** True when the two instances compared as equivalent. */
    public fun isEmpty(): Boolean = changes.isEmpty()

    /** True when at least one change was found. */
    public fun isNotEmpty(): Boolean = changes.isNotEmpty()

    /**
     * Every change this diff holds followed by every change [other] holds.
     *
     * Concatenation, not reconciliation: two changes at one path are both kept, in the order given.
     * Nothing here decides what two conflicting changes mean, because nothing here can.
     */
    public operator fun plus(other: Diff): Diff = when {
        changes.isEmpty() -> other
        other.changes.isEmpty() -> this
        else -> Diff(changes + other.changes)
    }

    /** The same changes grouped into a hierarchy mirroring the object graph. */
    public fun tree(): DiffNode = buildTree(changes)

    /** The same changes as human-readable text, one line each. */
    public fun render(): String = renderChanges(changes)

    override fun equals(other: Any?): Boolean = this === other || (other is Diff && changes == other.changes)

    override fun hashCode(): Int = changes.hashCode()

    /**
     * The diff's [render]ing, so a diff reaching a log line, a debugger or an assertion message reads
     * as a diff rather than as a nested constructor call.
     *
     * Several lines for several changes, which is unusual for a `toString` and deliberate: a
     * single-line form would have to invent a third rendering of the same changes.
     */
    override fun toString(): String = renderChanges(changes)

    public companion object {
        /** The diff that found nothing, for a caller with nothing to report. */
        public val EMPTY: Diff = Diff(emptyList())
    }
}

/** A diff over the changes given, so a caller assembling changes need not build a list first. */
public fun Diff(vararg changes: Change): Diff = Diff(changes.asList())

/**
 * The changes reported *at* [property] itself, and nothing nested beneath it.
 *
 * The property is named by reference rather than by text, so no string is matched and a rename of the
 * property reaches this call site.
 *
 * It does **not** check that the property belongs to the diffed type unless you say which type that
 * is. `Diff` carries no type argument, so [T] is inferred from [property] alone and
 * `orderDiff.at(Address::street)` compiles, matching by name and finding nothing. Naming the type
 * restores the check:
 *
 * ```
 * orderDiff.at<Order>(Order::billing)     // checked
 * orderDiff.at<Order>(Address::street)    // does not compile
 * orderDiff.at(Address::street)           // compiles, and matches nothing
 * ```
 *
 * [Diff.route] does not have this hole, because its type argument is given at the call site and every
 * property it names is checked against it. Prefer routing when dispatching on several properties;
 * this is for narrowing one.
 */
public fun <T> Diff.at(property: KProperty1<T, *>): Diff =
    narrow { it.size == 1 && it.first().let { segment -> segment is Segment.Field && segment.name == property.name } }

/**
 * The changes reported at [property] *or anywhere beneath it*.
 *
 * The counterpart of `route`'s `under`: [at] is the property changing, this is the property or
 * anything inside it changing. [T] is inferred from [property] exactly as in [at] — name the type to
 * have it checked.
 */
public fun <T> Diff.under(property: KProperty1<T, *>): Diff =
    narrow { it.firstOrNull().let { segment -> segment is Segment.Field && segment.name == property.name } }

private inline fun Diff.narrow(keep: (List<Segment>) -> Boolean): Diff {
    val kept = changes.filter { keep(it.path.segments) }
    return if (kept.size == changes.size) this else Diff(kept)
}
