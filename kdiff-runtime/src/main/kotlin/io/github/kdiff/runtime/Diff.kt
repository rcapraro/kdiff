package io.github.kdiff.runtime

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
public data class ValueChanged(
    override val path: FieldPath,
    public val before: Any?,
    public val after: Any?,
) : Change {
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
public data class Moved(
    override val path: FieldPath,
    public val from: Int,
    public val to: Int,
) : Change {
    override fun prefixedWith(segment: Segment): Moved = copy(path = path.prefixedWith(segment))
}

/**
 * The same change relocated under [outer] and then [inner], in one path copy rather than two.
 *
 * The two-segment counterpart of [Change.prefixedWith], for the collection helpers that lift a nested
 * change under its element and then under its property. Internal for the reason the [FieldPath]
 * overload is: one-segment lifting is the contract, this is the shortcut.
 */
internal fun Change.prefixedWith(outer: Segment, inner: Segment): Change =
    withPath(path.prefixedWith(outer, inner))

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
 */
public data class Diff(public val changes: List<Change>) {
    /** True when the two instances compared as equivalent. */
    public val isEmpty: Boolean
        get() = changes.isEmpty()

    /** The same changes grouped into a hierarchy mirroring the object graph. */
    public fun tree(): DiffNode = buildTree(changes)

    /** The same changes as human-readable text, one line each. */
    public fun render(): String = renderChanges(changes)
}
