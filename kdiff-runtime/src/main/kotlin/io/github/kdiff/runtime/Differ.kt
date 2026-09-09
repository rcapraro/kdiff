package io.github.kdiff.runtime

/**
 * Compares two instances of [T] and reports how the second differs from the first.
 *
 * Implementations are generated for `@Diffable` data classes. The same interface is the target for
 * hand-written differs, so a generated differ and a hand-written one compose interchangeably when
 * one type nests another.
 *
 * Implementations must be pure: [diff] never modifies either argument, and diffing the same pair
 * twice returns equal results.
 *
 * One input is refused rather than compared. A list matched by key requires that key to identify at
 * most one element in each list; two elements sharing one leaves the comparison with no result it
 * could report, since a path names a keyed element by its key value alone. [diff] throws
 * [DuplicateDiffKeyException] for such a list rather than silently comparing one of them.
 *
 * One structure is refused too. [diff] descends no deeper than [MAX_DESCENT] nested delegations and
 * throws [CyclicStructureException] past that, so a cyclic graph is reported rather than exhausting
 * the stack.
 */
public fun interface Differ<T> {
    public fun diff(before: T, after: T): Diff
}
