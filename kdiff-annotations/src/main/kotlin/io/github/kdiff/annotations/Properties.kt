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
