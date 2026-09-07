package io.github.kdiff.runtime

/**
 * Rebuilds an instance of [T] with a list of changes applied.
 *
 * Implemented by generated code, which is the only thing that can construct the type it serves. A
 * hand-written differ built with the `differ { }` DSL can read properties but not construct their
 * owner, so [Differ] deliberately does not require this.
 *
 * Implementations must be pure: [apply] never modifies its argument, and applying the same changes
 * twice returns equal results.
 *
 * A change that cannot be applied is reported in [PatchResult.failures] rather than raised. The one
 * exception is an instance [apply] cannot interpret at all: a list matched by key requires that key to
 * identify at most one element, and two elements sharing one make [apply] throw
 * [DuplicateDiffKeyException] instead of returning a result. A cyclic source is refused the same way,
 * with [CyclicStructureException]. Neither is a change failing — it is the
 * source being unrebuildable, so there is no partial value to hand back.
 */
public interface Patcher<T> {
    public fun apply(before: T, changes: List<Change>): PatchResult<T>
}

/**
 * The outcome of applying changes: the rebuilt [value], and every change that could not be applied.
 *
 * Changes that could be applied are applied even when others fail, so [value] is always usable and
 * [failures] says exactly what it is missing. A caller wanting strictness checks [isClean].
 *
 * A result is produced whenever one can be. Where [Patcher.apply] throws instead, there is no result
 * at all rather than an empty one.
 */
public data class PatchResult<T>(public val value: T, public val failures: List<PatchFailure> = emptyList()) {
    /** True when every change was applied. */
    public val isClean: Boolean
        get() = failures.isEmpty()

    /**
     * [value] when every change applied, or [PatchFailedException] carrying every failure.
     *
     * For the caller who wants a patch to be all-or-nothing. Applying itself stays partial — that is
     * what lets a caller salvage what applied — so this is the call site's choice, not the patcher's.
     */
    public fun getOrThrow(): T {
        if (failures.isNotEmpty()) throw PatchFailedException(failures)
        return value
    }
}

/**
 * A change that could not be applied, and why.
 *
 * [reason] is a case rather than a sentence, so a caller can branch on why a change did not apply and
 * read off which property, key or index it concerned. [toString] renders the sentence, so a failure
 * logged as text reads as it always did.
 */
public data class PatchFailure(public val change: Change, public val reason: Reason) {
    override fun toString(): String = "${change.path}: ${reason.describe()}"

    /**
     * Every reason kdiff can decline to apply a change.
     *
     * Sealed, and closed on purpose, exactly as [Change] is: a caller handles these exhaustively, so
     * adding a case is a breaking change to a published vocabulary.
     */
    public sealed interface Reason {
        /** The path named no property the type compares. [type] is the type that has no such property. */
        public data class UnknownProperty(public val type: String) : Reason

        /** The property is compared by a differ that cannot reconstruct its type. */
        public data class UnpatchableProperty(public val property: String) : Reason

        /** The property is not a constructor parameter, so it cannot be rebuilt through `copy`. */
        public data class NotConstructorProperty(public val property: String) : Reason

        /** The change addressed something beneath a property compared as an opaque value. */
        public data object NotApplicableToValue : Reason

        /** The change's path did not identify an element of the keyed list it addressed. */
        public data object NotApplicableToKeyedList : Reason

        /** The change's path did not identify an element of the positional list it addressed. */
        public data object NotApplicableToPositionalList : Reason

        /** The change's path did not identify an entry of the map it addressed. */
        public data object NotApplicableToMap : Reason

        /** The property was null on the source, so there was nothing beneath it to patch. */
        public data object NothingBeneathNull : Reason

        /** No element of the keyed list carried the key the change named. */
        public data object NoElementForKey : Reason

        /** The positional list was shorter than the index the change named. */
        public data object NoElementAtIndex : Reason

        /** No entry of the map carried the key the change named. */
        public data object NoEntryForKey : Reason

        /** The list's elements are compared as opaque values, so one cannot be patched in place. */
        public data object ElementComparedAsValue : Reason

        /** The map's values are compared as opaque values, so one cannot be patched in place. */
        public data object EntryComparedAsValue : Reason

        /** A set element has no identity, so it can be added or removed but never modified. */
        public data object SetElementNotModifiable : Reason
    }
}

/**
 * The sentence a reason renders as.
 *
 * Exhaustive by construction, like `Change.withPath`: a new [PatchFailure.Reason] case fails to
 * compile here rather than rendering as nothing.
 */
internal fun PatchFailure.Reason.describe(): String = when (this) {
    is PatchFailure.Reason.UnknownProperty -> "$type has no compared property at this path"
    is PatchFailure.Reason.UnpatchableProperty -> "$property is compared by a differ that cannot patch"
    is PatchFailure.Reason.NotConstructorProperty -> "$property: $NOT_CONSTRUCTOR"
    PatchFailure.Reason.NotApplicableToValue -> "not applicable to a value property"
    PatchFailure.Reason.NotApplicableToKeyedList -> "not applicable to a keyed list"
    PatchFailure.Reason.NotApplicableToPositionalList -> "not applicable to a positional list"
    PatchFailure.Reason.NotApplicableToMap -> "not applicable to a map"
    PatchFailure.Reason.NothingBeneathNull -> "nothing to patch beneath a null property"
    PatchFailure.Reason.NoElementForKey -> "no element with this key to patch"
    PatchFailure.Reason.NoElementAtIndex -> "no element at this index to patch"
    PatchFailure.Reason.NoEntryForKey -> "no entry with this key to patch"
    PatchFailure.Reason.ElementComparedAsValue -> "element is compared as a value"
    PatchFailure.Reason.EntryComparedAsValue -> "entry value is compared as a value"
    PatchFailure.Reason.SetElementNotModifiable -> "a set element cannot be modified in place"
}

/**
 * Changes sorted into the property each belongs to, plus those belonging to no known property.
 *
 * Grouping first is what lets a generated `apply` rebuild through a single `copy`: reconstructing a
 * type needs the final value of every constructor property at once, and a property with several
 * changes beneath it is rebuilt once rather than once per change.
 */
public class GroupedChanges internal constructor(
    private val byProperty: Map<String, List<Change>>,
    public val unmatched: List<Change>,
) {
    /** The changes under [property], each with that property's segment stripped from its path. */
    public fun forProperty(property: String): List<Change> = byProperty[property].orEmpty()
}

/**
 * Sorts [changes] by the property their path starts with, keeping only [properties] the type
 * actually compares. Anything else lands in [GroupedChanges.unmatched] and is reported.
 */
public fun groupByProperty(changes: List<Change>, properties: Set<String>): GroupedChanges {
    val byProperty = mutableMapOf<String, MutableList<Change>>()
    val unmatched = mutableListOf<Change>()

    changes.forEach { change ->
        val first = change.path.segments.firstOrNull()
        if (first !is Segment.Field || first.name !in properties) {
            unmatched += change
            return@forEach
        }
        byProperty.getOrPut(first.name) { mutableListOf() } += change.withoutFirstSegment()
    }

    return GroupedChanges(byProperty, unmatched)
}

/** Reports every change that matched no compared property of [type]. */
public fun GroupedChanges.unmatchedFailures(type: String): List<PatchFailure> =
    unmatched.map { PatchFailure(it, PatchFailure.Reason.UnknownProperty(type)) }

internal fun Change.withoutFirstSegment(): Change = withPath(path.withoutFirst())
