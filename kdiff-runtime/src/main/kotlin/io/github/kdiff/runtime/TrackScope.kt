package io.github.kdiff.runtime

/**
 * The depth at which no change is excluded for lying too deep.
 *
 * Declared again in `kdiff-annotations` as the default of `@Trackable`. An annotation default has to
 * be a compile-time constant in the module declaring the annotation, and neither module may depend on
 * the other: that one carries no dependencies at all, and this one carries none but the Kotlin
 * standard library.
 */
public const val UNLIMITED_DEPTH: Int = -1

/**
 * One tracked property of a [TrackScope]: its [name], and how deep beneath it changes are reported.
 *
 * [depth] is counted in property steps from the tracked object, so `1` reports the property itself and
 * `2` reaches one level into it. It must be at least 1, or [UNLIMITED_DEPTH].
 */
public data class TrackedField(public val name: String, public val depth: Int) {
    init {
        require(depth == UNLIMITED_DEPTH || depth >= 1) {
            "$name: depth must be at least 1, or UNLIMITED_DEPTH; was $depth"
        }
    }
}

/**
 * What a tracker reports on: the properties worth hearing about, and how deep to follow each.
 *
 * A scope is reached two ways, and a tracker cannot tell them apart. `@Trackable` makes the generated
 * differ expose one through [Tracked]; `trackScope { }` builds the same thing by hand for a type whose
 * source cannot be annotated. Either substitutes for the other at any call site.
 *
 * A scope naming no property tracks every compared property, at [depth]. One naming properties tracks
 * exactly those, each at its own depth.
 */
public class TrackScope<T> internal constructor(
    internal val fields: List<TrackedField>?,
    internal val depth: Int?,
) {
    /**
     * The properties this scope names.
     *
     * Null when it names none *and* so tracks every compared property — what `trackScope { }` builds
     * with no `field` or `under` call. An empty list is the opposite, and reachable: it names none
     * because there are none to name, so nothing is tracked beyond a change at the object itself.
     * That is what a `@Trackable` class whose every compared property is `@TrackIgnore`d declares.
     */
    public val trackedFields: List<TrackedField>?
        get() = fields

    /** This scope with [depth] applied to every property it names, or unchanged when null. */
    internal fun atDepth(depth: Int?): TrackScope<T> = when {
        depth == null -> this
        fields == null -> TrackScope(null, depth)
        else -> TrackScope(fields.map { it.copy(depth = depth) }, depth)
    }
}

/**
 * A type whose declared tracking scope is known at compile time.
 *
 * Implemented by the differ generated for a `@Trackable` class, alongside the comparison and
 * application it already carries, so that one declaration serves all three. A tracker reads the scope
 * from here whenever its caller names no property of its own.
 */
public interface Tracked<T> {
    public val trackScope: TrackScope<T>
}

/**
 * Builds a scope from an explicit list of tracked properties.
 *
 * The entry point generated code uses, so that a declared scope and a hand-written one are the same
 * value built the same way. Prefer `trackScope { }` by hand: it names properties by reference rather
 * than by string.
 *
 * Passing no property means exactly that — nothing is tracked beyond a change at the object itself.
 * It is not the same as `trackScope { }`, which names none because the caller wants them all; see
 * [TrackScope.trackedFields].
 */
public fun <T> trackScopeOf(vararg fields: TrackedField): TrackScope<T> =
    TrackScope(fields.toList(), depth = null)
