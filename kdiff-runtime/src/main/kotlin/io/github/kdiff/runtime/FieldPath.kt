package io.github.kdiff.runtime

/**
 * One step in a [FieldPath].
 */
public sealed interface Segment {
    /** Navigation into the property called [name]. */
    public data class Field(public val name: String) : Segment

    /** Navigation to position [index] of a collection compared by position. */
    public data class Index(public val index: Int) : Segment

    /**
     * Navigation to the element or entry identified by [value], where [property] names what the
     * value identifies it by — a key property for a list, or `key` for a map entry.
     *
     * The key is retained as itself rather than as text: an entry added to a `Map<Int, V>` can only
     * be reconstructed from an `Int`, and a rendered `"1"` cannot be turned back into one.
     */
    public data class Key(public val property: String, public val value: Any?) : Segment
}

/**
 * Where in an object graph a [Change] was found, as a path from the root being diffed.
 *
 * An empty path denotes the root object itself.
 */
@JvmInline
public value class FieldPath(public val segments: List<Segment>) {

    /** This path with [segment] inserted at the front, used when a nested differ's result is lifted into its parent. */
    public fun prefixedWith(segment: Segment): FieldPath = FieldPath(listOf(segment) + segments)

    /**
     * The path rendered so a reader can follow it back to the source: properties separated by
     * dots, an index in square brackets, a key as its property and value in square brackets.
     */
    override fun toString(): String = buildString {
        segments.forEach { segment ->
            when (segment) {
                is Segment.Field -> {
                    if (isNotEmpty()) append('.')
                    append(segment.name)
                }
                is Segment.Index -> append("[${segment.index}]")
                is Segment.Key -> append("[${segment.property}=${segment.value}]")
            }
        }
    }

    public companion object {
        public val ROOT: FieldPath = FieldPath(emptyList())

        public fun of(name: String): FieldPath = FieldPath(listOf(Segment.Field(name)))
    }
}
