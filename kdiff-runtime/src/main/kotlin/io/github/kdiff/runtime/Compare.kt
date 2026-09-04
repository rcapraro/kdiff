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
    addAll(differ.diff(before, after).changes.map { it.prefixedWith(Segment.Field(name)) })
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
 * Compares a list whose elements carry an identity, matching by [keyOf] rather than by position so
 * that a reordered element reports as moved instead of as a removal and an addition.
 */
public fun <T> MutableList<Change>.compareKeyedList(
    name: String,
    keyProperty: String,
    before: List<T>,
    after: List<T>,
    differ: Differ<T>,
    keyOf: (T) -> Any?,
) {
    val beforeByKey = before.withIndex().associateBy { keyOf(it.value) }
    val afterByKey = after.withIndex().associateBy { keyOf(it.value) }

    beforeByKey.forEach { (key, old) ->
        val new = afterByKey[key]
        val element = Segment.Key(keyProperty, key)
        if (new == null) {
            add(Removed(FieldPath(listOf(element)), old.value).prefixedWith(Segment.Field(name)))
            return@forEach
        }
        if (old.index != new.index) {
            add(Moved(FieldPath(listOf(element)), old.index, new.index).prefixedWith(Segment.Field(name)))
        }
        differ.diff(old.value, new.value).changes.forEach { change ->
            add(change.prefixedWith(element).prefixedWith(Segment.Field(name)))
        }
    }

    afterByKey.forEach { (key, new) ->
        if (key in beforeByKey) return@forEach
        val element = Segment.Key(keyProperty, key)
        add(Added(FieldPath(listOf(element)), new.value).prefixedWith(Segment.Field(name)))
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
    val shared = minOf(before.size, after.size)

    for (index in 0 until shared) {
        val element = Segment.Index(index)
        if (differ == null) {
            if (before[index] != after[index]) {
                add(ValueChanged(FieldPath(listOf(element)), before[index], after[index]).prefixedWith(Segment.Field(name)))
            }
            continue
        }
        differ.diff(before[index], after[index]).changes.forEach { change ->
            add(change.prefixedWith(element).prefixedWith(Segment.Field(name)))
        }
    }

    for (index in shared until after.size) {
        add(Added(FieldPath(listOf(Segment.Index(index))), after[index]).prefixedWith(Segment.Field(name)))
    }
    for (index in shared until before.size) {
        add(Removed(FieldPath(listOf(Segment.Index(index))), before[index]).prefixedWith(Segment.Field(name)))
    }
}

/**
 * Compares a set by membership.
 *
 * Never reports a move or a modified element: set elements have no stable identity, so a modified
 * element is indistinguishable from one removed and another added.
 */
public fun <T> MutableList<Change>.compareSet(name: String, before: Set<T>, after: Set<T>) {
    val field = Segment.Field(name)
    (before - after).forEach { add(Removed(FieldPath.ROOT, it).prefixedWith(field)) }
    (after - before).forEach { add(Added(FieldPath.ROOT, it).prefixedWith(field)) }
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
            add(Removed(FieldPath(listOf(entry)), old).prefixedWith(field))
            return@forEach
        }
        val new = after.getValue(key)
        if (differ == null) {
            if (old != new) add(ValueChanged(FieldPath(listOf(entry)), old, new).prefixedWith(field))
            return@forEach
        }
        differ.diff(old, new).changes.forEach { add(it.prefixedWith(entry).prefixedWith(field)) }
    }

    after.forEach { (key, new) ->
        if (key in before) return@forEach
        add(Added(FieldPath(listOf(Segment.Key("key", key))), new).prefixedWith(field))
    }
}
