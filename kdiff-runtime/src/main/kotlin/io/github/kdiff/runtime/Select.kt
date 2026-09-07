package io.github.kdiff.runtime

/**
 * A scope with both routes resolved into the one question a tracker asks of each change: report it?
 *
 * Two implementations rather than one carrying a nullable field list, because the requirement *A
 * tracking scope with no selectors tracks the whole object* turns on their being kept apart: a scope
 * naming no property because its caller wants them all is [Everything], and one naming none because
 * there are none to name is [Named] over an empty map. Conflating those two is how a tracker comes to
 * report the whole object in silence, so it is made unrepresentable rather than merely avoided.
 *
 * A change at the tracked object itself — a sealed subclass swap — belongs to no property, so no
 * selector could name it and suppressing it would hide the object being replaced wholesale. Both
 * implementations report it, which is why each opens by testing for an empty path.
 */
internal sealed interface ResolvedScope {

    fun selects(change: Change): Boolean

    /** Every compared property, to [depth] property steps. */
    class Everything(private val depth: Int, private val excluded: Set<String>) : ResolvedScope {
        override fun selects(change: Change): Boolean {
            val segments = change.path.segments
            if (segments.isEmpty()) return true

            // Before any depth rule, so that a depth stated elsewhere cannot widen an exclusion back.
            if (change.path.rootName() in excluded) return false

            return within(propertySteps(segments), depth)
        }
    }

    /**
     * Exactly the properties [depths] names, each to its own depth.
     *
     * [depths] is folded once, when the scope is resolved, so asking about a change is a lookup rather
     * than a walk of the selectors — the question is asked once per change per update.
     */
    class Named(private val depths: Map<String, Int>, private val excluded: Set<String>) : ResolvedScope {
        override fun selects(change: Change): Boolean {
            val segments = change.path.segments
            if (segments.isEmpty()) return true

            val root = segments.first() as? Segment.Field ?: return false
            if (root.name in excluded) return false

            val limit = depths[root.name] ?: return false
            return within(propertySteps(segments), limit)
        }
    }
}

/** Whether a change lying [steps] property steps beneath the tracked object is shallow enough. */
private fun within(steps: Int, limit: Int): Boolean = limit == UNLIMITED_DEPTH || steps <= limit

// Indexed rather than `count { }`, which allocates an iterator on a path this runs over per change.
private fun propertySteps(segments: List<Segment>): Int {
    var steps = 0
    for (index in segments.indices) if (segments[index] is Segment.Field) steps++
    return steps
}

/**
 * The properties a scope names, folded to one depth each.
 *
 * Naming one property twice takes the widest depth, not the first written: each selector is a request
 * to track something, so order must not decide which of them is honoured.
 */
private fun namedScope(fields: List<TrackedField>, excluded: Set<String>): ResolvedScope.Named {
    val depths = HashMap<String, Int>(fields.size * 2)
    fields.forEach { field ->
        val widest = depths[field.name]
        depths[field.name] = when {
            widest == null -> field.depth
            widest == UNLIMITED_DEPTH || field.depth == UNLIMITED_DEPTH -> UNLIMITED_DEPTH
            else -> maxOf(widest, field.depth)
        }
    }
    return ResolvedScope.Named(depths, excluded)
}

/**
 * Resolves a caller's scope against whatever the differ's type declared.
 *
 * Naming a property at the call site replaces the declared scope outright, so that a reader of the
 * call site knows what will fire without consulting the type. A depth stated at the call site instead
 * overrides the declared depths, which is the one way to reach deeper than the type's author chose.
 */
// The cast cannot be checked because both types are generic, and it is safe because a differ
// implements Tracked for its own T or not at all. A failed test yields null, which is the
// "declares no scope" path.
@Suppress("UNCHECKED_CAST")
internal fun <T> TrackScope<T>.resolveAgainst(differ: Differ<T>): ResolvedScope {
    fields?.let { return namedScope(it, excluded) }

    val declared = (differ as? Tracked<T>)?.trackScope
    val declaredFields = declared?.fields
    val effectiveExcluded = excluded + declared?.excluded.orEmpty()

    // The only place either implementation is chosen, so the distinction the requirement turns on is
    // decided once, here, rather than inferred from a nullable field at every call.
    if (declaredFields == null) {
        return ResolvedScope.Everything(depth ?: declared?.depth ?: UNLIMITED_DEPTH, effectiveExcluded)
    }

    val atCallSiteDepth = if (depth != null) declaredFields.map { it.copy(depth = depth) } else declaredFields
    return namedScope(atCallSiteDepth, effectiveExcluded)
}

/**
 * A change's two sides, handed to [action] rather than returned as a pair, so reporting a change to
 * its listeners allocates nothing to describe it.
 *
 * Exhaustive by construction: a sixth [Change] would fail to compile here rather than be dropped in
 * silence. A move's sides are its two indices — they are genuinely its old and new state, and the
 * alternative is withholding moves from the callback without saying so.
 */
internal inline fun Change.withSides(action: (before: Any?, after: Any?) -> Unit) {
    when (this) {
        is ValueChanged -> action(before, after)
        is TypeChanged -> action(before, after)
        is Added -> action(null, value)
        is Removed -> action(value, null)
        is Moved -> action(from, to)
    }
}
