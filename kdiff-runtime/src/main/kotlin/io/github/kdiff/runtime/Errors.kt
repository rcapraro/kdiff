package io.github.kdiff.runtime

/*
 * The inputs kdiff refuses, and the one result it declines to hand back.
 *
 * Each is a declared type carrying the facts of the refusal as properties, so a caller can act on
 * which list, which key or which path was at fault instead of matching the message. The two refusals
 * extend `IllegalArgumentException`, which is what the runtime raised before they existed, so a
 * `catch` written against that keeps working.
 *
 * There is deliberately no common `KdiffException` supertype: a marker interface cannot be caught,
 * and a shared class would have to root at one of the two stdlib types and misfile the other.
 */

/**
 * The deepest nesting kdiff descends into before refusing the structure.
 *
 * High enough that no honest model reaches it, low enough to stay well inside a default JVM stack:
 * each level of nesting costs several frames. Not configurable — a per-call bound would have to be
 * threaded through [Differ], whose signature is the contract generated code is written against.
 *
 * Counted in nested delegations, which is the same thing tracking depth counts, but the two are
 * unrelated bounds: this one refuses a structure, tracking depth filters a change.
 */
public const val MAX_DESCENT: Int = 512

/**
 * Two or more elements of a list matched by key share one key.
 *
 * A path names a keyed element by its key value alone, so such a list has no representable diff and
 * no rebuildable form. Uniqueness is a property of the data rather than of the declaration, so this
 * cannot be a compile error.
 *
 * [property] and [keyProperty] are null when the caller had no name to give — a hand-written
 * [Patcher] calling a runtime helper directly — and the message omits them rather than being built
 * round placeholders.
 */
public class DuplicateDiffKeyException internal constructor(
    public val property: String?,
    public val keyProperty: String?,
    public val key: Any?,
) : IllegalArgumentException(message(property, keyProperty, key)) {
    private companion object {
        fun message(property: String?, keyProperty: String?, key: Any?): String =
            if (property == null || keyProperty == null) {
                "two elements of a keyed list share the key $key, " +
                    "and a keyed element must be uniquely identified."
            } else {
                "$property is keyed by $keyProperty, but two elements share the key $key. " +
                    "A keyed element must be uniquely identified; " +
                    "$property[$keyProperty=$key] cannot name one of them."
            }
    }
}

/**
 * A structure nested deeper than [MAX_DESCENT], which kdiff refuses rather than exhausting the stack.
 *
 * [path] is where the descent stopped. [repeated] says whether an instance was re-entered along that
 * path — a genuine cycle — or the structure is merely deeper than the bound, which is the difference
 * between a model that is wrong and a bound that is too low.
 *
 * Identity is only recorded near the bound, so [repeated] is false for a cycle longer than the last
 * stretch of the descent. The message says as much rather than claiming there is no cycle.
 */
public class CyclicStructureException internal constructor(public val path: FieldPath, public val repeated: Boolean) :
    IllegalArgumentException(message(path, repeated)) {
    private companion object {
        fun message(path: FieldPath, repeated: Boolean): String {
            val where = if (path.segments.isEmpty()) "the root" else "$path"
            return if (repeated) {
                "kdiff stopped at $where after $MAX_DESCENT steps: the same instance was reached " +
                    "again along that path, so the structure contains a cycle."
            } else {
                "kdiff stopped at $where after $MAX_DESCENT steps. No instance was seen twice in the " +
                    "steps before the limit, so this is a structure deeper than kdiff descends rather " +
                    "than a cycle it recognised."
            }
        }
    }
}

/**
 * A [PatchResult] was asked for a value it does not have, because at least one change failed.
 *
 * Raised only by [PatchResult.getOrThrow]; applying itself returns the partial result. Carries every
 * [failures] entry, so catching this is no less informative than inspecting the result.
 */
public class PatchFailedException internal constructor(public val failures: List<PatchFailure>) :
    IllegalStateException(
        "${failures.size} of the changes could not be applied: " +
            failures.joinToString("; "),
    )
