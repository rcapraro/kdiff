## ADDED Requirements

### Requirement: A routing can be nested under a property and dispatch at that property's type

The library SHALL let a routing declare a frame beneath one property, naming that property by
property reference, and dispatch the changes found beneath it against the property's own type. A frame
SHALL offer every route a routing offers — a handler for a property, a handler for the elements of a
collection property in both its keyed and unkeyed forms, and a fallback — and frames SHALL nest to
any depth the model has.

A frame SHALL dispatch exactly as routing the framed value's own diff would: the same handlers run,
receiving the same changes at paths rooted at the framed property's type, in the same order. Routing
a nested value through a frame and comparing that value directly and routing the result SHALL be
indistinguishable to the handlers.

A frame's property SHALL count as named in the enclosing routing. Changes beneath it SHALL NOT also
reach the enclosing fallback, and naming that property in both a frame and another handler of the
same routing SHALL be rejected, as naming any property twice already is.

A change that no handler inside a frame names SHALL be reported to the frame's own fallback when it
declares one. When it declares none, that change SHALL be returned to the enclosing routing, which
applies its own rules to it — its fallback when it declares one, and no failure when it does not —
so that one fallback at the outermost routing sees every change no handler at any depth named. A
change returned to an enclosing routing SHALL be reported at the path the enclosing routing received
it at, not at the path its frame dispatched it at.

A frame dispatches against the property's declared type, so the properties it can name are the ones
that type declares. Where the declared type is sealed, those are the properties the sealed type
declares itself; a subclass's own properties SHALL NOT be nameable in the frame. Where the declared
type is a collection, a path beneath the property begins with an element rather than a property, so
no handler in the frame can name it — naming the elements of a collection is what a collection handler
is for.

A change reported exactly at the framed property, rather than beneath it, has nothing left to dispatch
against the property's type — the value change a nullable nested value reports when it appears or
disappears is such a change. A frame SHALL treat it as unhandled rather than deliver it to a handler,
the way a change at the root of a routed type is already treated as unhandled.

#### Scenario: A frame dispatches on the framed property's own properties

- **WHEN** a diff of `Person` reports a change at `identity.civilStatus`
- **AND** it is routed with a frame naming `Person::identity` containing a handler naming
  `CivilIdentity::civilStatus`
- **THEN** that handler runs once, receiving the change

#### Scenario: A frame is equivalent to routing the nested value's own diff

- **WHEN** the same transition is routed once through a frame naming `Person::identity` and once by
  comparing the two `CivilIdentity` values directly and routing that diff
- **THEN** the same handlers run, with the same changes at the same paths, in the same order

#### Scenario: Frames nest to a further level

- **WHEN** a diff of `Person` reports changes at `identity.name.given` and `identity.name.family`
- **AND** it is routed with a frame naming `Person::identity` containing a frame naming
  `CivilIdentity::name` containing a handler naming `FullName::given`
- **THEN** the handler naming `FullName::given` runs once, receiving the change at `given`

#### Scenario: A handler for a property with no change inside a frame does not run

- **WHEN** a diff reports a change at `identity.civilStatus` and none under `identity.name`
- **AND** the frame naming `Person::identity` declares handlers for both
- **THEN** only the handler naming `CivilIdentity::civilStatus` runs

#### Scenario: A keyed collection inside a frame supplies its element key

- **WHEN** a diff of `Person` reports changes at `fiscal.crs[country=FR].tin` and
  `fiscal.crs[country=FR].certifiedOn`
- **AND** it is routed with a frame naming `Person::fiscal` containing a keyed element handler for
  `FiscalProfile::crs` keyed by `CrsRecord::country`
- **THEN** the changed-element handler runs once, receiving the key `FR` at the key property's own
  type

#### Scenario: A frame's changes do not reach the enclosing fallback

- **WHEN** a diff reports a change at `identity.civilStatus`
- **AND** it is routed with a frame naming `Person::identity` that handles it, and an enclosing
  fallback
- **THEN** the enclosing fallback does not receive that change

#### Scenario: A change no handler inside a frame names reaches the enclosing fallback

- **WHEN** a diff reports a change at `identity.civilStatus`
- **AND** it is routed with a frame naming `Person::identity` that declares no handler for
  `civilStatus` and no fallback of its own, and an enclosing fallback
- **THEN** the enclosing fallback receives that change, reported at `identity.civilStatus`

#### Scenario: A frame's own fallback consumes what its handlers did not name

- **WHEN** that same diff is routed with a frame that declares its own fallback
- **THEN** the frame's fallback receives the change and the enclosing fallback does not

#### Scenario: A change at the framed property itself is unhandled

- **WHEN** a diff reports a value change at `fiscal` because a nullable nested value became null
- **AND** it is routed with a frame naming `Person::fiscal` and an enclosing fallback
- **THEN** no handler inside the frame runs for it
- **AND** the enclosing fallback receives that change, reported at `fiscal`

#### Scenario: An unhandled change inside a frame with no fallback anywhere is ignored

- **WHEN** a diff reports a change inside a frame that no handler names, and neither the frame nor
  the enclosing routing declares a fallback
- **THEN** routing completes without failing and without running any handler for it

#### Scenario: A frame over a sealed property names the properties the sealed type declares

- **WHEN** a diff reports a change beneath `Person::employment`, whose declared type is sealed and
  declares `since` itself
- **AND** it is routed with a frame naming `Person::employment` containing a handler naming
  `Employment::since`
- **THEN** that handler runs once, receiving the change at `since`

#### Scenario: A subclass swap under a frame is unhandled

- **WHEN** a diff reports a type change at `employment` because the value became a different subclass
- **AND** it is routed with a frame naming `Person::employment` and an enclosing fallback
- **THEN** no handler inside the frame runs for it
- **AND** the enclosing fallback receives that change, reported at `employment`

#### Scenario: A frame over a collection property leaves every change unhandled

- **WHEN** a diff reports elements removed from `Person::tags` at `tags[0]` and `tags[1]`
- **AND** it is routed with a frame naming `Person::tags` and an enclosing fallback
- **THEN** no handler inside the frame runs
- **AND** the enclosing fallback receives both changes, reported at `tags[0]` and `tags[1]`

#### Scenario: Naming a property in both a frame and another handler is rejected

- **WHEN** a routing declares a frame naming `Person::identity` and also a handler naming
  `Person::identity`
- **THEN** it is rejected rather than running one and dropping the other

#### Scenario: A nested routing behaves the same for a hand-written differ

- **WHEN** the same transition is compared once by a generated differ and once by a hand-written one
- **AND** both diffs are routed with the same frames and handlers
- **THEN** the same handlers run, with the same values, in the same order
