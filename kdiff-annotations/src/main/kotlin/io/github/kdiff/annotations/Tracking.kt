package io.github.kdiff.annotations

/**
 * The depth at which no change is excluded for lying too deep, and the default depth of [Trackable].
 *
 * Declared again in `kdiff-runtime` for the `trackScope { }` DSL. An annotation default has to be a
 * compile-time constant in the module declaring the annotation, and neither module may depend on the
 * other: neither depends on any kdiff module or third-party library, and each carries nothing but the
 * Kotlin standard library.
 */
public const val UNLIMITED_DEPTH: Int = -1

/**
 * Declares a tracking scope for a `@Diffable` class: every compared property is tracked, at [depth].
 *
 * Tracking reports the changes a caller asked to hear about, and a scope is what they asked for. This
 * annotation is the declarative half; `trackScope { }` builds the same scope by hand for a type whose
 * source cannot be annotated. The generated differ exposes the declared scope, and a tracker uses it
 * for any caller that names no field of its own.
 *
 * Opt a property out with [TrackIgnore]; give one its own depth with [TrackDepth]. As with
 * `@Diffable`, there is no property-level way to opt *in*: the class opts in and properties opt out.
 *
 * [depth] is counted in property steps from the tracked object, so `1` reports the class's own
 * properties and `2` reaches one level into a nested type. A collection index or key is not a step: an
 * element added to a `List<Address>` property is reported at depth 1, and a change inside that element
 * at depth 2. A change lying deeper than [depth] is not reported at all — it is never summarised as a
 * change at a shallower property.
 *
 * Requires `@Diffable` on the same class: without it no differ is generated, so nothing could carry
 * the scope. [depth] must be at least 1, or [UNLIMITED_DEPTH].
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Trackable(val depth: Int = UNLIMITED_DEPTH)

/**
 * Excludes a property from its class's declared tracking scope, as `@DiffIgnore` excludes one from
 * comparison.
 *
 * The property is still compared: a diff reports its changes exactly as before, and only tracking
 * passes over it. Requires [Trackable] on the class — with no declared scope there would be nothing
 * to exclude the property from.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class TrackIgnore

/**
 * Sets the depth at which one property is tracked, taking precedence over its class's [Trackable]
 * depth for that property alone.
 *
 * Requires [Trackable] on the class. [depth] must be at least 1, or [UNLIMITED_DEPTH], and is counted
 * the same way [Trackable.depth] is.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class TrackDepth(val depth: Int)
