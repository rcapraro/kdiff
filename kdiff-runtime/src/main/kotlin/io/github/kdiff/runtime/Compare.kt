package io.github.kdiff.runtime

/**
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
public fun <T> MutableList<Change>.compareNested(
    name: String,
    before: T,
    after: T,
    differ: Differ<T>,
) {
    val field = Segment.Field(name)
    differ.diff(before, after).changes.forEach { add(it.prefixedWith(field)) }
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
internal fun duplicateKey(name: String?, keyProperty: String?, key: Any?): Nothing {
    // Named for the two routes that have names to give — a generated differ and a `keyedList`
    // builder. A hand-written patcher calling the helper directly has neither, and a message
    // built round placeholders reads worse than one that simply omits them.
    val message = if (name == null || keyProperty == null) {
        "two elements of a keyed list share the key $key, " +
            "and a keyed element must be uniquely identified."
    } else {
        "$name is keyed by $keyProperty, but two elements share the key $key. " +
            "A keyed element must be uniquely identified; " +
            "$name[$keyProperty=$key] cannot name one of them."
    }
    throw IllegalArgumentException(message)
}

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
 */
public fun <T> MutableList<Change>.compareKeyedList(
    name: String,
    keyProperty: String,
    before: List<T>,
    after: List<T>,
    differ: Differ<T>,
    keyOf: (T) -> Any?,
) {
    val field = Segment.Field(name)
    val beforeByKey = indexByKey(name, keyProperty, before, keyOf)
    val afterByKey = indexByKey(name, keyProperty, after, keyOf)

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

    afterByKey.forEach { (key, newPosition) ->
        if (key in beforeByKey) return@forEach
        add(Added(FieldPath(listOf(field, Segment.Key(keyProperty, key))), after[newPosition]))
    }
}

/**
 * Compares a list whose elements carry no identity, index by index. A move is never reported: with
 * no key there is nothing to recognise a moved element by.
 */
public fun <T> MutableList<Change>.comparePositionalList(
    name: String,
    before: List<T>,
    after: List<T>,
    differ: Differ<T>?,
) {
    val field = Segment.Field(name)
    val shared = minOf(before.size, after.size)

    for (index in 0 until shared) {
        val element = Segment.Index(index)
        if (differ == null) {
            if (before[index] != after[index]) {
                add(ValueChanged(FieldPath(listOf(field, element)), before[index], after[index]))
            }
            continue
        }
        differ.diff(before[index], after[index]).changes.forEach { change ->
            add(change.prefixedWith(field, element))
        }
    }

    for (index in shared until after.size) {
        add(Added(FieldPath(listOf(field, Segment.Index(index))), after[index]))
    }
    for (index in shared until before.size) {
        add(Removed(FieldPath(listOf(field, Segment.Index(index))), before[index]))
    }
}

/**
 * Compares a set by membership.
 *
 * Never reports a move or a modified element: set elements have no stable identity, so a modified
 * element is indistinguishable from one removed and another added.
 */
public fun <T> MutableList<Change>.compareSet(name: String, before: Set<T>, after: Set<T>) {
    // Walked rather than subtracted: `before - after` copies the whole receiver into a new set before
    // removing anything, and membership is all either side is being asked about.
    val path = FieldPath.of(name)
    before.forEach { if (it !in after) add(Removed(path, it)) }
    after.forEach { if (it !in before) add(Added(path, it)) }
}

/** Compares a map by entry key, delegating to [differ] for values it can descend into. */
public fun <K, V> MutableList<Change>.compareMap(
    name: String,
    before: Map<K, V>,
    after: Map<K, V>,
    differ: Differ<V>?,
) {
    val field = Segment.Field(name)

    before.forEach { (key, old) ->
        val entry = Segment.Key("key", key)
        if (key !in after) {
            add(Removed(FieldPath(listOf(field, entry)), old))
            return@forEach
        }
        val new = after.getValue(key)
        if (differ == null) {
            if (old != new) add(ValueChanged(FieldPath(listOf(field, entry)), old, new))
            return@forEach
        }
        differ.diff(old, new).changes.forEach { add(it.prefixedWith(field, entry)) }
    }

    after.forEach { (key, new) ->
        if (key in before) return@forEach
        add(Added(FieldPath(listOf(field, Segment.Key("key", key))), new))
    }
}
