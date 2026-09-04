package io.github.kdiff.annotations

/**
 * Marks a type as diffable.
 *
 * Annotating `Person` makes a `PersonDiffer` available in the same package, implementing
 * `Differ<Person>`. Nothing else needs to be written or registered.
 *
 * Accepted on data classes, and on sealed classes and sealed interfaces whose subclasses are all
 * themselves `@Diffable` — a sealed type's differ dispatches on the runtime subclass. Applying this
 * annotation to any other declaration fails the compilation with an error reported at that
 * declaration.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Diffable
