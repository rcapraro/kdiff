package io.github.kdiff.runtime

/*
 * The helpers generated `apply` implementations call, one per rebuilt property.
 *
 * Each returns the property's new value together with any change it could not use, so the caller
 * can gather failures and still produce an instance.
 */

/** A rebuilt property value and whatever could not be applied to it. */
public data class Patched<T>(public val value: T, public val failures: List<PatchFailure> = emptyList())

internal const val NOT_CONSTRUCTOR = "only constructor properties can be reconstructed"

/** Takes the new value from the last value change at this property, ignoring none of the rest. */
public fun <T> patchValue(source: T, changes: List<Change>): Patched<T> {
    if (changes.isEmpty()) return Patched(source)

    val failures = mutableListOf<PatchFailure>()
    var value = source

    changes.forEach { change ->
        when {
            change is ValueChanged && change.path.segments.isEmpty() -> {
                @Suppress("UNCHECKED_CAST")
                value = change.after as T
            }

            change is TypeChanged && change.path.segments.isEmpty() -> {
                @Suppress("UNCHECKED_CAST")
                value = change.after as T
            }

            else -> failures += PatchFailure(change, PatchFailure.Reason.NotApplicableToValue)
        }
    }

    return Patched(value, failures)
}

/**
 * Rebuilds a nested value by handing its changes to [patcher].
 *
 * Changes at the property itself are the patcher's business too — a sealed patcher resolves a
 * subclass swap and the parent-property changes reported alongside it — so everything is delegated
 * rather than being split here.
 */
public fun <T> patchNested(source: T, changes: List<Change>, patcher: Patcher<T>): Patched<T> {
    if (changes.isEmpty()) return Patched(source)

    return Descent.into(NESTED, source) {
        val result = patcher.apply(source, changes)
        Patched(result.value, result.failures)
    }
}

/**
 * The step a patch descent records.
 *
 * Applying works through changes whose paths have already had the property segment stripped, so the
 * helper no longer knows the property name it is rebuilding. The path a refusal reports therefore
 * counts the levels rather than naming them, which is enough to say how deep the structure went.
 */
private val NESTED = Segment.Field("<nested>")

/**
 * Rebuilds a nullable property of any shape, delegating to [patch] when there is a value to rebuild.
 *
 * The counterpart of `compareNestedNullable`'s rule on the applying side, and the one place it is
 * written: a value change at the property itself sets it wholesale — that is how a null transition is
 * reported — nothing can be rebuilt beneath a null, and anything deeper is delegated only when there
 * is an instance to delegate to.
 *
 * [patch] is the helper that rebuilds the shape in question, so a nullable nested value, list, set or
 * map all reach the same rule. It carries whatever descent step its shape takes; this adds none of its
 * own, because the null cases recurse into nothing.
 *
 * Only a [ValueChanged] *at* the property is this helper's own business. Everything else is delegated,
 * an empty path included: a set reports its own elements there, and a sealed nested value reports a
 * subclass swap there, and both belong to the shape's helper.
 */
public fun <C : Any> patchNullable(
    source: C?,
    changes: List<Change>,
    patch: (C, List<Change>) -> Patched<C>,
): Patched<C?> {
    val wholesale = changes.lastOrNull { it is ValueChanged && it.path.segments.isEmpty() }
    val delegated = if (wholesale == null) changes else changes.filterNot { it === wholesale }

    // Delegated even where the outcome below discards the result, and even for an empty change list: a
    // present value meets its shape's preconditions whether or not anything is applied to it, and
    // delegating is the only way to reach them. `patchKeyedList` examines the list it is handed before
    // its own empty-changes exit, precisely so that carrying a property through cannot skip the check —
    // and a nullable declaration of that property must not behave differently from a non-null one.
    // Every shape without a precondition returns at once on an empty list, so this costs them nothing.
    val rebuilt = source?.let { patch(it, delegated) }

    if (wholesale is ValueChanged) {
        // Replaced outright, which makes the property a value here: the last change at it wins and
        // everything else addressed to it could not be used. Reported rather than dropped, and with
        // the reason `patchValue` gives its own leftovers, since this is that same situation.
        @Suppress("UNCHECKED_CAST")
        return Patched(
            wholesale.after as C?,
            delegated.map { PatchFailure(it, PatchFailure.Reason.NotApplicableToValue) },
        )
    }

    if (rebuilt == null) {
        return Patched(null, changes.map { PatchFailure(it, PatchFailure.Reason.NothingBeneathNull) })
    }

    return Patched(rebuilt.value, rebuilt.failures)
}

/**
 * Rebuilds a nullable nested value.
 *
 * A change at the property itself sets it wholesale — that is how a null transition was reported —
 * and anything deeper is delegated only when there is an instance to delegate to. [patchNested]
 * carries the descent step, as it does for a non-null nested property.
 */
public fun <T : Any> patchNestedNullable(source: T?, changes: List<Change>, patcher: Patcher<T>): Patched<T?> =
    patchNullable(source, changes) { value, nested -> patchNested(value, nested, patcher) }

/** Reports every change beneath a property whose differ cannot patch (design D7). */
public fun <T> unpatchable(source: T, changes: List<Change>, property: String): Patched<T> =
    Patched(source, changes.map { PatchFailure(it, PatchFailure.Reason.UnpatchableProperty(property)) })

/** Reports every change targeting a property that is not a constructor parameter. */
public fun <T> notConstructorProperty(source: T, changes: List<Change>, property: String): Patched<T> =
    Patched(source, changes.map { PatchFailure(it, PatchFailure.Reason.NotConstructorProperty(property)) })

/**
 * Applies to an `object` subclass of a sealed type by returning it, reporting every change as
 * addressing a property [type] does not have.
 *
 * A singleton has no state, so it has no property a change could name and nothing to rebuild. Any
 * change reaching it therefore did not come from comparing it, which is what
 * [PatchFailure.Reason.UnknownProperty] says. A subclass swap never arrives here: it is resolved at the
 * root, before dispatch, and carries the target instance.
 *
 * Returns a [PatchResult] rather than a [Patched] because it is a whole `apply`, not one rebuilt
 * property — it stands in for the branch a data class subclass fills with a call to its own patcher.
 */
public fun <T> patchSingleton(before: T, changes: List<Change>, type: String): PatchResult<T> =
    PatchResult(before, changes.map { PatchFailure(it, PatchFailure.Reason.UnknownProperty(type)) })

/**
 * Rebuilds a keyed list by computing its target state rather than replaying operations, so that no
 * index is read off a list that is being mutated (design D5).
 *
 * A key identifies at most one element in [source]. Two elements sharing one is a
 * [DuplicateDiffKeyException], raised whatever [changes] holds and an empty list included: such a list
 * cannot be rebuilt on its own terms, because the map this works through holds one entry per key while
 * the source holds two elements, so one would be written out twice and the other lost.
 *
 * Reaching this at all takes a change addressed to the list, since a caller that short-circuits on an
 * empty change list — [patchNested] does — never calls in. So a duplicate nested under an untouched
 * property is passed through rather than rejected. It is not rebuilt either, so nothing is lost.
 *
 * [name] and [keyProperty] appear only in that message and default to null, so a hand-written
 * [Patcher] calling this directly keeps compiling and gets a message that omits them rather than one
 * built round placeholders. Generated code passes both.
 */
public fun <T> patchKeyedList(
    source: List<T>,
    changes: List<Change>,
    patcher: Patcher<T>,
    name: String? = null,
    keyProperty: String? = null,
    keyOf: (T) -> Any?,
): Patched<List<T>> {
    // Insertion-ordered, so this map is the element order as well as the lookup, and a repeated key is
    // rejected by the build itself. The empty-changes exit sits after it rather than before: carrying
    // an unaddressed property through skips the rebuild, never a precondition its shape requires.
    val byKey = LinkedHashMap<Any?, T>(source.size * 2)
    source.forEachIndexed { position, element ->
        val key = keyOf(element)
        byKey[key] = element
        // Size, not `put`'s return value: `T` may itself be nullable, and a null element returns null
        // from `put` whether or not its key was already present, which would let a repeat through and
        // silently drop an element — the exact loss this check exists to prevent.
        if (byKey.size != position + 1) duplicateKey(name, keyProperty, key)
    }

    if (changes.isEmpty()) return Patched(source)

    val failures = mutableListOf<PatchFailure>()
    val moves = mutableMapOf<Any?, Int>()
    val elementChanges = mutableMapOf<Any?, MutableList<Change>>()

    changes.forEach { change ->
        val key = (change.path.segments.firstOrNull() as? Segment.Key)?.value
        if (key == null) {
            failures += PatchFailure(change, PatchFailure.Reason.NotApplicableToKeyedList)
            return@forEach
        }
        val rest = change.withoutFirstSegment()
        when {
            change is Removed && rest.path.segments.isEmpty() -> byKey.remove(key)

            change is Added && rest.path.segments.isEmpty() -> {
                // A re-`put` leaves an existing key at its original position, which is what an
                // addition of a key already present should do.
                @Suppress("UNCHECKED_CAST")
                byKey[key] = change.value as T
            }

            change is Moved && rest.path.segments.isEmpty() -> moves[key] = change.to

            else -> elementChanges.getOrPut(key) { mutableListOf() } += rest
        }
    }

    elementChanges.forEach { (key, elementChange) ->
        val element = byKey[key]
        if (element == null) {
            failures += elementChange.map { PatchFailure(it, PatchFailure.Reason.NoElementForKey) }
            return@forEach
        }
        val result = Descent.into(NESTED, element) { patcher.apply(element, elementChange) }
        byKey[key] = result.value
        failures += result.failures
    }

    val positioned = byKey.keys.toList()
    val target = arrayOfNulls<Any?>(positioned.size)
    val unplaced = mutableListOf<Any?>()

    positioned.forEach { key ->
        val to = moves[key]
        if (to != null && to in target.indices && target[to] == null) target[to] = key else unplaced += key
    }
    var next = 0
    unplaced.forEach { key ->
        while (next < target.size && target[next] != null) next++
        if (next < target.size) target[next] = key
    }

    @Suppress("UNCHECKED_CAST")
    return Patched(target.filterNotNull().map { byKey.getValue(it) }, failures)
}

/** Rebuilds a positional list: no keys, so no moves and no identity beyond the index. */
public fun <T> patchPositionalList(source: List<T>, changes: List<Change>, patcher: Patcher<T>?): Patched<List<T>> {
    if (changes.isEmpty()) return Patched(source)

    val failures = mutableListOf<PatchFailure>()
    val elements = source.toMutableList()
    val removed = sortedSetOf<Int>()
    val added = sortedMapOf<Int, T>()
    val elementChanges = mutableMapOf<Int, MutableList<Change>>()

    changes.forEach { change ->
        val index = (change.path.segments.firstOrNull() as? Segment.Index)?.index
        if (index == null) {
            failures += PatchFailure(change, PatchFailure.Reason.NotApplicableToPositionalList)
            return@forEach
        }
        val rest = change.withoutFirstSegment()
        when {
            change is Removed && rest.path.segments.isEmpty() -> removed += index

            change is Added && rest.path.segments.isEmpty() -> {
                @Suppress("UNCHECKED_CAST")
                added[index] = change.value as T
            }

            else -> elementChanges.getOrPut(index) { mutableListOf() } += rest
        }
    }

    elementChanges.forEach { (index, elementChange) ->
        if (index !in elements.indices) {
            failures += elementChange.map { PatchFailure(it, PatchFailure.Reason.NoElementAtIndex) }
            return@forEach
        }
        if (patcher == null) {
            val value = elementChange.lastOrNull { it is ValueChanged && it.path.segments.isEmpty() }
            if (value is ValueChanged) {
                @Suppress("UNCHECKED_CAST")
                elements[index] = value.after as T
            } else {
                failures += elementChange.map { PatchFailure(it, PatchFailure.Reason.ElementComparedAsValue) }
            }
            return@forEach
        }
        val element = elements[index]
        val result = Descent.into(NESTED, element) { patcher.apply(element, elementChange) }
        elements[index] = result.value
        failures += result.failures
    }

    val kept = elements.filterIndexed { index, _ -> index !in removed }.toMutableList()
    added.forEach { (index, value) -> kept.add(minOf(index, kept.size), value) }

    return Patched(kept, failures)
}

/** Rebuilds a set by membership: drop removals, add additions, never reorder. */
public fun <T> patchSet(source: Set<T>, changes: List<Change>): Patched<Set<T>> {
    if (changes.isEmpty()) return Patched(source)

    val failures = mutableListOf<PatchFailure>()
    val elements = source.toMutableSet()

    changes.forEach { change ->
        when {
            change is Removed && change.path.segments.isEmpty() -> elements.remove(change.value)

            change is Added && change.path.segments.isEmpty() -> {
                @Suppress("UNCHECKED_CAST")
                elements += change.value as T
            }

            else -> failures += PatchFailure(change, PatchFailure.Reason.SetElementNotModifiable)
        }
    }

    return Patched(elements, failures)
}

/** Rebuilds a map by entry key, taking keys from the path segment rather than its rendering. */
public fun <K, V> patchMap(source: Map<K, V>, changes: List<Change>, patcher: Patcher<V>?): Patched<Map<K, V>> {
    if (changes.isEmpty()) return Patched(source)

    val failures = mutableListOf<PatchFailure>()
    val entries = source.toMutableMap()
    val entryChanges = mutableMapOf<K, MutableList<Change>>()

    changes.forEach { change ->
        val segment = change.path.segments.firstOrNull() as? Segment.Key
        if (segment == null) {
            failures += PatchFailure(change, PatchFailure.Reason.NotApplicableToMap)
            return@forEach
        }

        @Suppress("UNCHECKED_CAST")
        val key = segment.value as K
        val rest = change.withoutFirstSegment()
        when {
            change is Removed && rest.path.segments.isEmpty() -> entries.remove(key)

            change is Added && rest.path.segments.isEmpty() -> {
                @Suppress("UNCHECKED_CAST")
                entries[key] = change.value as V
            }

            else -> entryChanges.getOrPut(key) { mutableListOf() } += rest
        }
    }

    entryChanges.forEach { (key, entryChange) ->
        val value = entries[key]
        if (value == null) {
            failures += entryChange.map { PatchFailure(it, PatchFailure.Reason.NoEntryForKey) }
            return@forEach
        }
        if (patcher == null) {
            val replacement = entryChange.lastOrNull { it is ValueChanged && it.path.segments.isEmpty() }
            if (replacement is ValueChanged) {
                @Suppress("UNCHECKED_CAST")
                entries[key] = replacement.after as V
            } else {
                failures += entryChange.map { PatchFailure(it, PatchFailure.Reason.EntryComparedAsValue) }
            }
            return@forEach
        }
        val result = Descent.into(NESTED, value) { patcher.apply(value, entryChange) }
        entries[key] = result.value
        failures += result.failures
    }

    return Patched(entries, failures)
}
