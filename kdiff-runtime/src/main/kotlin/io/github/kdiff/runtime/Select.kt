package io.github.kdiff.runtime

/**
 * A scope with both routes resolved into the one question a tracker asks of each change: report it?
 *
 * [fields] null means every compared property is tracked, at [depth].
 */
internal class ResolvedScope(
    private val fields: List<TrackedField>?,
    private val depth: Int,
) {
    fun selects(change: Change): Boolean {
        val segments = change.path.segments

        // A change at the tracked object itself — a sealed subclass swap — belongs to no property, so
        // no selector could name it and suppressing it would hide the object being replaced wholesale.
        if (segments.isEmpty()) return true

        val steps = segments.count { it is Segment.Field }
        if (fields == null) return within(steps, depth)

        val root = segments.first() as? Segment.Field ?: return false

        // Naming one property twice takes the widest depth, not the first written: each selector is a
        // request to track something, so order must not decide which of them is honoured.
        val depths = fields.filter { it.name == root.name }.map { it.depth }
        if (depths.isEmpty()) return false
        if (UNLIMITED_DEPTH in depths) return true
        return within(steps, depths.max())
    }

    private fun within(steps: Int, limit: Int): Boolean = limit == UNLIMITED_DEPTH || steps <= limit
}

/**
 * Resolves a caller's scope against whatever the differ's type declared.
 *
 * Naming a property at the call site replaces the declared scope outright, so that a reader of the
 * call site knows what will fire without consulting the type. A depth stated at the call site instead
 * overrides the declared depths, which is the one way to reach deeper than the type's author chose.
 */
internal fun <T> TrackScope<T>.resolveAgainst(differ: Differ<T>): ResolvedScope {
    fields?.let { return ResolvedScope(it, depth ?: UNLIMITED_DEPTH) }

    val declared = (differ as? Tracked<T>)?.trackScope
    val declaredFields = declared?.fields
    val effective = when {
        depth != null && declaredFields != null -> declaredFields.map { it.copy(depth = depth) }
        else -> declaredFields
    }
    return ResolvedScope(effective, depth ?: declared?.depth ?: UNLIMITED_DEPTH)
}

/**
 * A change's two sides, for the per-field callback.
 *
 * Exhaustive by construction: a sixth [Change] would fail to compile here rather than be dropped in
 * silence. A move's sides are its two indices — they are genuinely its old and new state, and the
 * alternative is withholding moves from the callback without saying so.
 */
internal fun Change.sides(): Pair<Any?, Any?> = when (this) {
    is ValueChanged -> before to after
    is TypeChanged -> before to after
    is Added -> null to value
    is Removed -> value to null
    is Moved -> from to to
}
