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
 */
public interface Differ<T> {
    public fun diff(before: T, after: T): Diff
}
