package io.github.kdiff.runtime

/*
 * The helpers generated differs call, one per compared property.
 *
 * They live here rather than being generated so that the comparison algorithms exist once, where a
 * fix ships as a dependency bump instead of a recompile of every consumer.
 */

/** Compares a property held by value, reporting a single [ValueChanged] when the two differ. */
public fun MutableList<Change>.compareValue(name: String, before: Any?, after: Any?) {
    if (before != after) add(ValueChanged(FieldPath.of(name), before, after))
}

/** Compares a non-null nested property by delegating to [differ] and lifting its paths under [name]. */
public fun <T> MutableList<Change>.compareNested(name: String, before: T, after: T, differ: Differ<T>) {
    val field = Segment.Field(name)
    Descent.into(field, before) {
        differ.diff(before, after).changes.forEach { add(it.prefixedWith(field)) }
    }
}

/**
 * Compares a nullable nested property.
 *
 * A null on either side is a value change, never an addition or removal: a property's path exists
 * on both sides or on neither.
 */
public fun <T : Any> MutableList<Change>.compareNestedNullable(
    name: String,
    before: T?,
    after: T?,
    differ: Differ<T>,
) {
    when {
        before == null && after == null -> Unit
        before == null || after == null -> add(ValueChanged(FieldPath.of(name), before, after))
        else -> compareNested(name, before, after, differ)
    }
}

/**
 * Reports the null side of a nullable collection property.
 *
 * The rule [compareNestedNullable] states, applied to the four collection helpers: one null side is a
 * value change at the property itself, and two nulls are no change at all. A property's path exists on
 * both sides or on neither, so a collection appearing or disappearing is never an addition or a removal
 * of the elements it would have held.
 *
 * Reached only where one side is already known to be null, which is why it tells the two cases apart by
 * identity: two nulls are the same reference, and one null is not.
 */
private fun MutableList<Change>.reportNullSide(name: String, before: Any?, after: Any?) {
    if (before !== after) add(ValueChanged(FieldPath.of(name), before, after))
}

/**
 * Rejects a keyed collection holding two elements that carry one key.
 *
 * A keyed comparison has no representable result for a repeated key: a path identifies an element by
 * its key value, so `addresses[id=A1]` could not say which of the two it means, and no change reported
 * there would be actionable. Matching one and discarding the rest loses an element silently, which is
 * what this replaces.
 *
 * Detection is not here. A caller indexing elements by key learns of the collision from the map it is
 * already building, so this only names it — leaving the check itself costing one `put` return value
 * rather than a size comparison and a second walk.
 */
internal fun duplicateKey(name: String?, keyProperty: String?, key: Any?): Nothing =
    // The message is built by the exception, so what it says and what a caller can read off it cannot
    // drift. Both routes that refuse such a list — comparing and applying — arrive here.
    throw DuplicateDiffKeyException(name, keyProperty, key)

/**
 * Each element's position, by key, in the order the elements appear.
 *
 * Insertion-ordered because the map's iteration order is the order changes are reported in, and a
 * keyed element's identity is the only thing that survives a reorder.
 */
internal fun <T> indexByKey(
    name: String?,
    keyProperty: String?,
    elements: List<T>,
    keyOf: (T) -> Any?,
): Map<Any?, Int> {
    val index = LinkedHashMap<Any?, Int>(elements.size * 2)
    elements.forEachIndexed { position, element ->
        val key = keyOf(element)
        index[key] = position
        // The map's size rather than `put`'s return value: a map whose values may themselves be null
        // returns null from `put` whether or not the key was already there, and a repeat would slip
        // past. Size cannot be fooled, and reading it costs nothing.
        if (index.size != position + 1) duplicateKey(name, keyProperty, key)
    }
    return index
}

/**
 * Compares a list whose elements carry an identity, matching by [keyOf] rather than by position so
 * that a reordered element reports as moved instead of as a removal and an addition.
 *
 * A key identifies at most one element in each list. Two elements sharing one is an
 * [IllegalArgumentException]: see [duplicateKey] for why such a comparison has no result to
 * report.
 *
 * A null on either side is a value change at the property, never an addition or a removal — see
 * [reportNullSide]. One call therefore serves a nullable property and a non-null one.
 *
 * A present side is indexed even on that path, so a repeated key is refused there too: having no
 * representable diff is a fact about the list itself, not about what it is being compared against, and
 * reporting the transition would otherwise hand the caller a list that the very next comparison of it
 * refuses.
 */
public fun <T> MutableList<Change>.compareKeyedList(
    name: String,
    keyProperty: String,
    before: List<T>?,
    after: List<T>?,
    differ: Differ<T>,
    keyOf: (T) -> Any?,
) {
    if (before == null || after == null) {
        before?.let { indexByKey(name, keyProperty, it, keyOf) }
        after?.let { indexByKey(name, keyProperty, it, keyOf) }
        return reportNullSide(name, before, after)
    }

    val field = Segment.Field(name)
    val beforeByKey = indexByKey(name, keyProperty, before, keyOf)
    val afterByKey = indexByKey(name, keyProperty, after, keyOf)

    // One step for the whole list, not one per element: what is being bounded is recursion, and a
    // nested level enters this helper exactly once however many elements it holds. Stepping per
    // element would cost per element and tighten nothing.
    Descent.into(field, before) {
        beforeByKey.forEach { (key, oldPosition) ->
            val element = Segment.Key(keyProperty, key)
            val newPosition = afterByKey[key]
            if (newPosition == null) {
                add(Removed(FieldPath(listOf(field, element)), before[oldPosition]))
                return@forEach
            }
            if (oldPosition != newPosition) {
                add(Moved(FieldPath(listOf(field, element)), oldPosition, newPosition))
            }
            differ.diff(before[oldPosition], after[newPosition]).changes.forEach { change ->
                add(change.prefixedWith(field, element))
            }
        }
    }

    afterByKey.forEach { (key, newPosition) ->
        if (key in beforeByKey) return@forEach
        add(Added(FieldPath(listOf(field, Segment.Key(keyProperty, key))), after[newPosition]))
    }
}

/**
 * Whether comparing these two elements would report nothing.
 *
 * The comparison the caller would otherwise have performed — equality for elements compared as values,
 * [differ] reporting no change for elements it descends into — and never equality standing in for a
 * differ. A type's `equals` can be looser than the properties its differ reads: an entity equal by
 * identity, or a compared property the generated `equals` does not cover. Excluding a position on
 * equality alone would drop a change the comparison would have reported, silently.
 */
private fun <T> agrees(old: T, new: T, differ: Differ<T>?): Boolean =
    if (differ == null) old == new else differ.diff(old, new).isEmpty()

/**
 * How many trailing positions the two lists agree on, taken one from the end of each.
 *
 * Bounded by the shorter list, which is the only guard needed: no leading run is excluded, because
 * the window's own walk already reports nothing across leading positions the two lists agree on.
 */
private fun <T> agreeingTail(before: List<T>, after: List<T>, differ: Differ<T>?): Int {
    var count = 0
    val bound = minOf(before.size, after.size)
    while (count < bound && agrees(before[before.size - 1 - count], after[after.size - 1 - count], differ)) {
        count++
    }
    return count
}

private fun <T> MutableList<Change>.compareWindow(
    field: Segment.Field,
    before: List<T>,
    after: List<T>,
    differ: Differ<T>?,
) {
    val agreeing = agreeingTail(before, after, differ)
    val shared = minOf(before.size, after.size) - agreeing

    if (differ == null) {
        for (index in 0 until shared) {
            if (before[index] != after[index]) {
                add(ValueChanged(FieldPath(listOf(field, Segment.Index(index))), before[index], after[index]))
            }
        }
    } else {
        for (index in 0 until shared) {
            val element = Segment.Index(index)
            differ.diff(before[index], after[index]).changes.forEach { change ->
                add(change.prefixedWith(field, element))
            }
        }
    }

    for (index in shared until after.size - agreeing) {
        add(Added(FieldPath(listOf(field, Segment.Index(index))), after[index]))
    }
    for (index in shared until before.size - agreeing) {
        add(Removed(FieldPath(listOf(field, Segment.Index(index))), before[index]))
    }
}

/**
 * Compares a list whose elements carry no identity, by position, after excluding the tail the two
 * lists already agree on — so one contiguous insertion or deletion reports as exactly that rather than
 * shifting every element after it.
 *
 * A removal is reported at its index in [before] and an addition at its index in [after], which is what
 * makes the result applicable: `patchPositionalList` removes against the source's own positions and
 * inserts against the target's.
 *
 * The two coordinates never have to disagree for an element change. Additions and removals begin
 * exactly where element changes stop, so an element change always names a position **both** lists
 * hold, and one comparison reports additions or removals but never both.
 *
 * Two lists of the same length are compared index by index, always. Excluding an agreeing tail from
 * two lists of equal length cannot change which positions are paired or what is reported at them, so
 * this is a guarantee rather than a coincidence: for a fixed-arity list the index *is* the element's
 * identity, and nothing here overrides it.
 *
 * A move is never reported: with no key there is nothing to recognise a moved element by.
 *
 * A null on either side is a value change at the property, never an addition or a removal — see
 * [reportNullSide]. One call therefore serves a nullable property and a non-null one.
 */
public fun <T> MutableList<Change>.comparePositionalList(
    name: String,
    before: List<T>?,
    after: List<T>?,
    differ: Differ<T>?,
) {
    if (before == null || after == null) return reportNullSide(name, before, after)

    val field = Segment.Field(name)

    // Elements compared as opaque values cannot recurse, so they take no descent step. Elements a
    // differ descends into take one step for the whole list — the tail scan included, since that scan
    // runs the same differ — for the reason `compareKeyedList` takes one: what is bounded is
    // recursion, and a nested level enters this helper exactly once however many elements it holds.
    if (differ == null) {
        compareWindow(field, before, after, null)
        return
    }
    Descent.into(field, before) { compareWindow(field, before, after, differ) }
}

/**
 * Compares a set by membership.
 *
 * Never reports a move or a modified element: set elements have no stable identity, so a modified
 * element is indistinguishable from one removed and another added.
 *
 * A null on either side is a value change at the property, never an addition or a removal — see
 * [reportNullSide]. One call therefore serves a nullable property and a non-null one.
 */
public fun <T> MutableList<Change>.compareSet(name: String, before: Set<T>?, after: Set<T>?) {
    if (before == null || after == null) return reportNullSide(name, before, after)

    // Walked rather than subtracted: `before - after` copies the whole receiver into a new set before
    // removing anything, and membership is all either side is being asked about.
    val path = FieldPath.of(name)
    before.forEach { if (it !in after) add(Removed(path, it)) }
    after.forEach { if (it !in before) add(Added(path, it)) }
}

/**
 * Compares a map by entry key, delegating to [differ] for values it can descend into.
 *
 * A null on either side is a value change at the property, never an addition or a removal — see
 * [reportNullSide]. One call therefore serves a nullable property and a non-null one.
 */
public fun <K, V> MutableList<Change>.compareMap(
    name: String,
    before: Map<K, V>?,
    after: Map<K, V>?,
    differ: Differ<V>?,
) {
    if (before == null || after == null) return reportNullSide(name, before, after)

    val field = Segment.Field(name)

    // Entries compared as opaque values cannot recurse, so they take no descent step — the same
    // split `comparePositionalList` makes, and for the same reason: a step that can never tighten
    // the bound is a step that only costs.
    if (differ == null) {
        before.forEach { (key, old) ->
            val entry = Segment.Key("key", key)
            if (key !in after) {
                add(Removed(FieldPath(listOf(field, entry)), old))
                return@forEach
            }
            val new = after.getValue(key)
            if (old != new) add(ValueChanged(FieldPath(listOf(field, entry)), old, new))
        }
    } else {
        Descent.into(field, before) {
            before.forEach { (key, old) ->
                val entry = Segment.Key("key", key)
                if (key !in after) {
                    add(Removed(FieldPath(listOf(field, entry)), old))
                    return@forEach
                }
                differ.diff(old, after.getValue(key)).changes.forEach { add(it.prefixedWith(field, entry)) }
            }
        }
    }

    after.forEach { (key, new) ->
        if (key in before) return@forEach
        add(Added(FieldPath(listOf(field, Segment.Key("key", key))), new))
    }
}
