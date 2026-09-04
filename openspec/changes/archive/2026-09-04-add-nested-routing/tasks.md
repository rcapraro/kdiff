## 1. Path descent

- [x] 1.1 Add the internal member on `FieldPath` that drops its first segment, and verify a
  `FieldPathSpec` case covers a multi-segment path, a single-segment path becoming the root path, and
  the root path itself
- [x] 1.2 Add the internal exhaustive `when` over the five `Change` variants that applies the descent,
  in the style of `Change.sides()`, and verify a Kotest case asserts each variant keeps its own type
  and payload while losing only the leading segment

## 2. Frame dispatch

- [x] 2.1 Change `internal fun ChangeRoutes.dispatch` to return the changes it did not account for,
  leaving `Diff.route` discarding the return, and verify `RouteSpec` still passes unchanged
- [x] 2.2 Add `under(property) { }` to `ChangeRoutes` — filter to the framed property, re-root, dispatch
  through a fresh `ChangeRoutes<V>`, pair the returned changes back to their originals by identity —
  and verify a `RouteSpec` case dispatches a handler naming a child property
- [x] 2.3 Register a frame's property in the same map `on` registers into, so naming a property in both
  a frame and another handler is rejected, and verify a `RouteSpec` case asserts the rejection
- [x] 2.4 Verify a `RouteSpec` case asserts a frame nests to a third level, dispatching
  `identity.name.given` to a handler naming `FullName::given` at the path `given`

## 3. Fallback composition

- [x] 3.1 Verify a `RouteSpec` case asserts a change no handler inside a frame names reaches the
  enclosing fallback at its original path, not at the frame's re-rooted path
- [x] 3.2 Verify a `RouteSpec` case asserts a frame's own fallback consumes what its handlers did not
  name, and that the enclosing fallback then receives nothing
- [x] 3.3 Verify a `RouteSpec` case asserts an unhandled change inside a frame with no fallback at any
  level completes without failing and runs no handler
- [x] 3.4 Verify a `RouteSpec` case asserts a value change reported at the framed property itself —
  a nullable nested value object appearing or disappearing — runs no handler in the frame and reaches
  the enclosing fallback at the property's path

## 4. Composition with the rest of the library

- [x] 4.1 Verify a `RouteSpec` case asserts a keyed `onEach` inside a frame supplies its element key at
  the key property's own type, from a path two levels deep
- [x] 4.2 Verify a `RouteSpec` case asserts a frame over a collection property propagates every change
  outward unhandled rather than failing
- [x] 4.3 Verify a `RouteSpec` case pins the equivalence the spec requires: routing a nested value
  through a frame and comparing that value directly and routing the result run the same handlers with
  the same changes at the same paths, in the same order
- [x] 4.4 Verify a `TrackedDiffSpec` case asserts a frame reports nothing the enclosing scope filtered
  out, so scope stays decided at the outermost comparison

## 5. Parity and documentation

- [x] 5.1 Add a two-level value object to `kdiff-tutorial` and route it through nested frames, and
  verify `PersonCommandSpec` asserts the events derived from it
- [x] 5.2 Verify `AnnotatedParitySpec` asserts the nested routing behaves identically against the
  annotated mirror and the hand-written differ
- [x] 5.3 Document the nested form in `docs/diffing.md` alongside the existing routing section, and
  verify `DocumentationSamplesSpec` compiles and runs the sample shown there
- [x] 5.4 Add the `Unreleased` entry to `CHANGELOG.md` describing `under` as an addition, written for
  someone deciding whether to upgrade

## 6. Done

- [x] 6.1 Run `./gradlew check` and verify it is green, reporting any failure verbatim
