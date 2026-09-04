## Why

`Diff.route` dispatches on the first segment of a change's path, so a routing reaches a type's own
properties and nothing deeper. A caller whose value objects are more than one level deep — the shape
`nested` exists to encourage — cannot route at the granularity its domain actually speaks in.

The workaround is to re-diff the child value object at its own type inside the parent's handler, so
its paths are re-rooted and `route` accepts them. It works, and it costs: the child differ runs a
second time, the parent's tracking scope no longer applies to the changes being routed, the
`current`/`desired` pair is threaded by hand at every level, and each nesting level grows its own
fallback handler to keep watch over.

`nested(property, differ)` already lifts a child differ's relative paths *up* under a property name.
Routing needs the descent, and does not have it: `Change.prefixedWith` adds a segment and nothing
removes one.

## What Changes

- `ChangeRoutes` gains `under(property) { }`, declaring a routing frame for the changes beneath one
  property, dispatched against the property's own type. Frames nest to any depth.
- Inside a frame, every existing route call means what it already means, one level down: `on`,
  `onEach` in both forms, and `otherwise`.
- A change sitting exactly *at* the framed property — the value change a nullable value object
  reports when it becomes null — has nothing left beneath it, and is treated as unhandled by the
  frame, the way `route` already treats a change at the root of the routed type.
- A framed property counts as named for the purpose of the enclosing routing, so its changes do not
  also reach the enclosing `otherwise`, and naming it twice is rejected as it is for `on`.
- `FieldPath` gains the descent counterpart of `prefixedWith`, and `Change` the operation that
  applies it, so a frame can re-root the changes it dispatches.
- Not breaking. Every addition is new API; no existing signature, path, change or diagnostic moves.
  A routing that declares no frame behaves exactly as it does today.

## Capabilities

### New Capabilities

None. Routing is already specified under `diff-generation`.

### Modified Capabilities

- `diff-generation`: the routing requirement gains nested frames — what a frame dispatches, how it
  re-roots paths, how a change at the framed property itself is treated, and how a frame interacts
  with the enclosing routing's fallback and duplicate-name rules.

## Impact

- **Modules**: `kdiff-runtime` only (`Route.kt`, `FieldPath.kt`, `Change` implementations). No change
  to `kdiff-annotations` or `kdiff-processor`.
- **Generated API surface**: unchanged. Routing reads a diff and never appears in generated code, so
  no consumer recompiles and `kdiff-sample`'s generated output is untouched.
- **Annotation semantics**: unchanged. No annotation gains, loses or alters a parameter.
- **Documentation**: `docs/diffing.md` covers routing and gains the nested form; `kdiff-tutorial`
  routes a two-level model and is where the frame gets exercised end to end.
- **Driver**: an external consumer deriving granular domain events from a diff, whose aggregate nests
  value objects two and three levels deep and gates service requests on properties inside them.
