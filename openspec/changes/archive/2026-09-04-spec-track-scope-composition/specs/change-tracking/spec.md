## ADDED Requirements

### Requirement: A prepared scope, a named property and a stated depth compose by one rule

A tracker may be given a scope prepared elsewhere, may have properties named at its own call site,
and may have a depth stated there. Where more than one of the three is present they SHALL combine as
follows:

- Naming a property at the call site SHALL replace a prepared scope outright. Naming a property is
  the more local statement, so a reader of the call site knows what will fire without consulting the
  prepared scope.
- A depth stated at the call site SHALL apply to the properties the prepared scope names. It SHALL
  NOT discard them.
- Stating a depth SHALL NEVER cause a property the resolved scope does not name to be reported. In
  particular, combining a prepared scope with a stated depth SHALL NOT fall back to tracking every
  compared property, and SHALL NOT fall back to a scope the tracked type declared.

The last rule holds however the tracker's differ is built: whether it declares a scope of its own or
declares none, a caller who named properties SHALL hear about those properties and no others.

Widening is singled out because it is the failure that cannot be noticed: a tracker reporting too
little is visible the first time an expected callback does not arrive, while a tracker reporting a
property the caller scoped out looks like a change they asked for.

#### Scenario: A stated depth applies to what a prepared scope names

- **WHEN** a tracker is given a prepared scope naming `Order::billing`, and a depth of 2 is stated at
  its call site
- **AND** the update differs at `billing.city`
- **THEN** the update reports the change at `billing.city`

#### Scenario: A stated depth does not widen a prepared scope

- **WHEN** a tracker is given a prepared scope naming `Order::reference` only, and a depth of 2 is
  stated at its call site
- **AND** the update differs at `reference`, at `status`, and at `billing.city`
- **THEN** the update reports one change, at `reference`

#### Scenario: A stated depth does not fall back to the type's declared scope

- **WHEN** the tracked type declares a scope naming `status`
- **AND** a tracker over it is given a prepared scope naming `Order::reference` only, with a depth
  stated at its call site
- **AND** the update differs at both `reference` and `status`
- **THEN** the update reports one change, at `reference`

#### Scenario: A property named at the call site replaces a prepared scope

- **WHEN** a tracker is given a prepared scope covering the subtree under `Order::billing`, and
  `Order::reference` is named at its call site
- **AND** the update differs at both `reference` and `billing.city`
- **THEN** the update reports one change, at `reference`

#### Scenario: A prepared scope is used when the call site names no property

- **WHEN** a tracker is given a prepared scope covering the subtree under `Order::billing` and names
  no property at its call site
- **AND** the update differs at `reference`, `billing.city` and `billing.country.code`
- **THEN** the update reports the changes at `billing.city` and `billing.country.code`
- **AND** it does not report the change at `reference`

### Requirement: A property named more than once is tracked at the widest depth named

Where a scope names the same property more than once, the property SHALL be tracked at the widest
depth named for it. An unlimited depth SHALL therefore win over any bounded depth, and of two bounded
depths the deeper SHALL win.

The order the selectors are written in SHALL NOT decide which is honoured: each names something the
caller wants reported, and a scope SHALL report everything it was asked to.

#### Scenario: An unlimited depth wins whichever order it is named in

- **WHEN** a scope names `Order::billing` itself and also names its whole subtree, in either order
- **AND** the update differs at `billing.city` and at `billing.country.code`
- **THEN** the update reports both changes
- **AND** both orders report the same changes

#### Scenario: The deeper of two bounded depths wins

- **WHEN** a scope names `Order::billing` at depth 2 and again at depth 3
- **AND** the update differs at `billing.city` and at `billing.country.code`
- **THEN** the update reports both changes

## MODIFIED Requirements

### Requirement: A tracking scope with no selectors tracks the whole object

A scope naming no property SHALL be one of two distinct things, and the library SHALL keep them
distinct.

A scope that names no property *because its caller wants them all*, and inherits no declared scope,
SHALL track every compared property at unlimited depth: every change the differ reports SHALL be
reported by the tracker. This is what building a scope by hand and naming nothing means, and it is
the default a tracker falls back to.

A scope built from an explicit, empty list of properties SHALL track none of them: it names no
property because there is none to name. A change at the tracked object itself SHALL still be
reported, as it is under any scope.

The two SHALL be distinguishable by a caller inspecting a scope, so that the second is not mistaken
for the first.

Keeping them apart is what lets a `@Trackable` class whose every compared property is `@TrackIgnore`d
declare a scope at all: its author excluded everything, and tracking everything would be the exact
opposite of what they asked for.

#### Scenario: An empty scope reports every change

- **WHEN** a tracker is created with a scope that declares no field and no depth
- **AND** the update differs at `reference`, at `billing.city`, and at `addresses[id=A2].street`
- **THEN** the update reports all three changes
- **AND** each change keeps the path the differ gave it

#### Scenario: A scope built from an explicit empty list reports nothing

- **WHEN** a tracker is created with a scope built from an explicit empty list of properties
- **AND** the update differs at `reference`
- **THEN** the update reports no changes

#### Scenario: An explicitly empty scope still reports a change at the tracked object

- **WHEN** a tracker for a sealed `Payment` is created with a scope built from an explicit empty list
- **AND** the update replaces a `Card` with a `Transfer`
- **THEN** the update reports the type change from `Card` to `Transfer`

#### Scenario: The two kinds of empty scope are distinguishable

- **WHEN** a caller inspects a scope that named no property and a scope built from an explicit empty
  list
- **THEN** the two report differently which properties they name
