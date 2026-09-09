package io.github.kdiff.annotations

import kotlin.reflect.KClass

/**
 * Marks the property that identifies an instance inside a collection.
 *
 * A list whose element type declares a key is compared by matching elements on it, so a reordered
 * element reports as moved and a changed one reports at its key — rather than the whole list
 * reporting as replaced. A type may declare at most one key.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class DiffKey

/** Excludes a property from comparison. It never contributes a change, however much it differs. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class DiffIgnore

/**
 * Compares this property with [differ] instead of by kdiff's own rules.
 *
 * The escape hatch for a property whose type cannot be annotated. [differ] must be an `object`
 * implementing `Differ` of the property's type; build one with the `differ { }` DSL.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class DiffWith(val differ: KClass<*>)

/**
 * Compares as a single value, by equality, reporting one change carrying both sides.
 *
 * On a class: every property, list element and map value of that type is compared by equality, in
 * every `@Diffable` class that reaches it. On a property: that property alone is compared by
 * equality whatever its type — a `@Diffable` type or a collection is reported as one change at the
 * property, with nothing beneath it. It is the annotation counterpart of the `differ { }` builder's
 * `field`.
 *
 * An annotation that could change nothing is a compile error: on an enum, a `value class`, a type
 * kdiff already compares as a value, or a property whose type is one of those. So is a
 * contradiction: beside `@Diffable` on a class, or beside `@DiffWith` or `@DiffIgnore` on a
 * property.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class DiffAsValue
