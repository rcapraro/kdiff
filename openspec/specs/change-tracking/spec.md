## Purpose

Defines what a caller gets when they ask to be told about changes rather than to compute them: the
baseline a tracker keeps, how the scope of what is worth reporting is declared — by hand or by
annotation — what depth means, what each callback receives for each kind of change, and how a
tracker's output composes with applying a diff.

## Requirements

### Requirement: A tracking scope can be written by hand for a type that cannot be annotated

The library SHALL provide a way to build a `TrackScope<T>` in ordinary Kotlin, naming the tracked
fields of `T` and their depths, for a type whose source cannot be annotated.

A hand-written scope SHALL be indistinguishable from a generated one to any code that consumes it: a
tracker SHALL behave identically whether its scope was declared by annotation or built by hand, and
SHALL NOT expose which route produced it.

Fields SHALL be named by property reference, so that a field that does not belong to the tracked
type SHALL NOT compile, and a renamed property SHALL be renamed at the call site by any refactoring
that renames it in the declaration.

A hand-written scope SHALL compose with a hand-written differ, so that a type carrying no kdiff
annotation at all can be both compared and tracked.

Building a scope standalone and declaring one inline while creating a tracker SHALL offer the same
members, with the same names, the same parameters and the same validation. Neither route SHALL accept
a scope the other rejects, or reject a scope the other accepts: a caller who learns one has learned
both.

#### Scenario: A scope built by hand tracks the fields it names

- **WHEN** a scope for `Money` is built by hand naming its `amount` property
- **AND** a tracker using it is updated with a `Money` whose `amount` and `currency` both differ
- **THEN** the update reports one change, at `amount`

#### Scenario: A hand-written scope is indistinguishable from a generated one

- **WHEN** one tracker is created with a hand-written scope and another with the scope declared by
  annotation on the same type, both naming the same fields at the same depths
- **THEN** both trackers report the same changes for the same update

#### Scenario: A type with no annotations at all can be tracked

- **WHEN** a differ for a third-party type is built by hand and a scope for it is built by hand
- **THEN** a tracker over that type reports the changes its scope selects
- **AND** the type carries no kdiff annotation

#### Scenario: A field reference from another type does not compile

- **WHEN** a scope for `Order` names a property reference belonging to `Address`
- **THEN** the code does not compile

#### Scenario: Declaring a scope inline offers exactly the standalone members

- **WHEN** a scope is declared inline while creating a tracker, using each member the standalone
  builder offers
- **THEN** the code compiles
- **AND** the tracker reports the changes the equivalent standalone scope selects

#### Scenario: The two routes reject the same scope

- **WHEN** a scope that one route rejects is declared through the other
- **THEN** it is rejected there too, with the same error

### Requirement: A tracker observes an evolving instance against a baseline

The library SHALL provide a tracker that holds one instance as its baseline and reports how each
later instance differs from it.

A tracker SHALL be created from a differ for the tracked type, an initial instance, and a scope. It
SHALL expose its baseline as `current`.

Updating a tracker with a new instance SHALL compare the baseline against that instance, report the
changes that match the scope, and then adopt the new instance as the baseline. The update SHALL
return the matching changes as a `Diff`, so a caller who prefers a return value over a callback
needs no callback.

An update SHALL NOT modify either instance, and SHALL NOT modify the underlying diff result.

#### Scenario: A tracker starts at its initial instance

- **WHEN** a tracker is created for `Order` from an instance `a`
- **THEN** `current` is `a`
- **AND** no callback has fired

#### Scenario: An update reports how the new instance differs from the baseline

- **WHEN** a tracker's baseline is `Order(reference = "R1")` and it is updated with
  `Order(reference = "R2")`
- **THEN** the update reports one value change at `reference` from `"R1"` to `"R2"`

#### Scenario: An update adopts the new instance as the baseline

- **WHEN** a tracker's baseline is `a`, it is updated with `b`, and it is then updated with `b`
  again
- **THEN** the first update reports the changes between `a` and `b`
- **AND** `current` is `b` after the first update
- **AND** the second update reports no changes

#### Scenario: An update with an equal instance reports nothing

- **WHEN** a tracker is updated with an instance equal to its baseline
- **THEN** the update reports no changes
- **AND** no callback fires

#### Scenario: An update does not mutate its instances

- **WHEN** a tracker with baseline `a` is updated with `b`
- **THEN** `a` and `b` are unchanged

### Requirement: A tracker's baseline can be reset without reporting

A tracker SHALL allow its baseline to be replaced without comparing and without firing any
callback, so a caller who has adopted a new state by other means can resynchronise.

#### Scenario: Resetting replaces the baseline silently

- **WHEN** a tracker with baseline `a` is reset to `b`, where `a` and `b` differ
- **THEN** no callback fires
- **AND** `current` is `b`
- **AND** a following update with `b` reports no changes

### Requirement: A scope can filter a single comparison without a tracker

The library SHALL let a caller apply a tracking scope to one comparison of two instances, reporting
exactly the changes a tracker carrying that scope would report for the same transition. It SHALL hold
no baseline and remember nothing, for the caller that already holds both instances and wants the
tracked view of the difference between them.

When no scope is given, the scope the differ's type declares SHALL apply; when the differ's type
declares none, every compared property SHALL be reported.

#### Scenario: A filtered comparison equals a tracker's report

- **WHEN** two instances are compared with a scope applied directly
- **AND** a tracker carrying the same scope is shown the same two instances
- **THEN** both report equal changes, in the same order

#### Scenario: A declared scope applies when none is given

- **WHEN** two instances of a type with a declared tracking scope are compared with no scope given
- **THEN** the declared scope decides what is reported

#### Scenario: Everything compared is reported when nothing is declared

- **WHEN** the differ's type declares no scope and none is given
- **THEN** every change the comparison found is reported

#### Scenario: A filtered comparison remembers nothing

- **WHEN** the same two instances are compared this way twice
- **THEN** both comparisons report the same changes

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
### Requirement: A tracking scope can select individual fields

A scope SHALL allow individual fields of the tracked type to be selected by property reference. Only
changes located under a selected field SHALL be reported; a change under an unselected field SHALL
NOT be reported.

Selecting a field without stating a depth SHALL track that field itself and nothing nested beneath
it.

#### Scenario: Only selected fields are reported

- **WHEN** a scope selects `Order::reference` only
- **AND** the update differs at both `reference` and `status`
- **THEN** the update reports one change, at `reference`

#### Scenario: A selected field does not imply its subtree

- **WHEN** a scope selects `Order::billing` only
- **AND** the update differs only at `billing.city`
- **THEN** the update reports no changes

#### Scenario: Selecting several fields reports each of them

- **WHEN** a scope selects `Order::reference` and `Order::status`
- **AND** the update differs at `reference`, `status` and `note`
- **THEN** the update reports the changes at `reference` and `status` and not the one at `note`

### Requirement: A tracking scope can select a field and its whole subtree

A scope SHALL allow a field to be selected together with everything nested beneath it, at unlimited
depth, as a single declaration.

#### Scenario: A subtree selection reports nested changes

- **WHEN** a scope selects the subtree under `Order::billing`
- **AND** the update differs at `billing.city`
- **THEN** the update reports the change at `billing.city`

#### Scenario: A subtree selection reports the field itself

- **WHEN** a scope selects the subtree under `Order::shipping` and `shipping` goes from an address
  to `null`
- **THEN** the update reports the value change at `shipping`

#### Scenario: A subtree selection does not widen to other fields

- **WHEN** a scope selects the subtree under `Order::billing`
- **AND** the update differs only at `reference`
- **THEN** the update reports no changes

### Requirement: A hand-written scope can name the properties it excludes

A tracking scope written by hand SHALL be able to name the properties it excludes instead of the
properties it tracks. A scope naming only exclusions SHALL track every compared property except those
named, at the scope's depth — the hand-written counterpart of excluding a property from an annotated
class's declared scope.

An excluded property SHALL never be reported, at any depth: neither the property itself nor anything
nested beneath it.

Naming exclusions and tracked properties in one scope SHALL be rejected, because the two say opposite
things about every property named in neither. Excluding a property the differ does not compare SHALL
be accepted and SHALL exclude nothing, since such a property can never appear in a change.

#### Scenario: Excluding one property tracks every other

- **WHEN** a scope for `Person` excludes `lastSeenAt` and names no tracked property
- **AND** a transition changes `name.family` and `lastSeenAt`
- **THEN** the change at `name.family` is reported and the change at `lastSeenAt` is not

#### Scenario: An excluded property's nested change is not reported either

- **WHEN** a scope excludes a property whose type nests further
- **AND** a transition changes something two steps beneath it
- **THEN** nothing is reported for it

#### Scenario: Mixing an exclusion with a tracked property is rejected

- **WHEN** a scope both names a tracked property and excludes another
- **THEN** it is rejected rather than resolved by a precedence rule

#### Scenario: Excluding a property that is never compared is harmless

- **WHEN** a scope excludes a property the differ does not compare
- **THEN** the scope is accepted and every compared property is still tracked

### Requirement: Depth bounds how deep a reported change may lie

A scope SHALL support a depth, which bounds how far into nested objects a change may lie and still
be reported.

Depth SHALL be counted in property steps from the tracked object. A change whose path contains more
property steps than the depth allows SHALL NOT be reported. A depth SHALL be declarable for the
whole scope, and a field's own declared depth SHALL take precedence over the scope's.

An unlimited depth SHALL be expressible, and SHALL mean that no change is excluded on the grounds of
its depth.

A depth of zero or a negative depth other than the unlimited marker SHALL be rejected where it is
declared, whether that is an annotation or a hand-written scope.

#### Scenario: Depth one reports the object's own fields

- **WHEN** a scope tracks every field at depth 1
- **AND** the update differs at `reference` and at `billing.city`
- **THEN** the update reports the change at `reference` only

#### Scenario: Depth two reports one level of nesting

- **WHEN** a scope tracks every field at depth 2
- **AND** the update differs at `reference`, `billing.city`, and `billing.country.code`
- **THEN** the update reports the changes at `reference` and `billing.city`
- **AND** it does not report the change at `billing.country.code`

#### Scenario: Unlimited depth excludes nothing

- **WHEN** a scope tracks every field at unlimited depth
- **AND** the update differs three levels down, at `company.address.city`
- **THEN** the update reports that change

#### Scenario: A field's declared depth wins over the scope's

- **WHEN** a scope tracks every field at depth 1 and declares depth 2 for `Order::billing`
- **AND** the update differs at `billing.city` and at `note`
- **THEN** the update reports both changes

#### Scenario: A zero depth in a hand-written scope is rejected

- **WHEN** a hand-written scope declares a depth of 0
- **THEN** building the scope fails with a message naming the offending field and the accepted values

### Requirement: Collection and map element identity does not consume depth

An element's position or key SHALL NOT count as a property step when a change's depth is
determined: an index or a key identifies a sibling within a collection, not a level of nesting.

#### Scenario: An element added to a list is reported at depth one

- **WHEN** a scope tracks every field at depth 1
- **AND** an element with key `A3` is added to the `addresses` list
- **THEN** the update reports the addition at `addresses[id=A3]`

#### Scenario: A change inside a list element is reported at depth two

- **WHEN** a scope tracks every field at depth 2
- **AND** the element with key `A2` of `addresses` differs at its `street`
- **THEN** the update reports the change at `addresses[id=A2].street`

#### Scenario: A change inside a list element is excluded at depth one

- **WHEN** a scope tracks every field at depth 1
- **AND** the only difference is at `addresses[id=A2].street`
- **THEN** the update reports no changes

#### Scenario: A map entry is reported at the same depth as a plain field

- **WHEN** a scope tracks every field at depth 1
- **AND** the entry `"eur"` of the `amounts` map holds a different value
- **THEN** the update reports the change at the path identifying entry `"eur"`

### Requirement: A change excluded by depth is dropped, not relocated

A change the depth excludes SHALL simply not be reported. It SHALL NOT be rewritten to a shallower
path, SHALL NOT be summarised as a change at its nearest reported ancestor, and SHALL NOT cause any
other change to be reported in its place.

Depth therefore selects which of the differ's changes reach the caller; it never invents a change,
alters a path, or produces a value the differ did not report.

#### Scenario: A deep change produces nothing at its ancestor

- **WHEN** a scope tracks every field at depth 1
- **AND** the only difference is at `billing.city`
- **THEN** the update reports no changes
- **AND** in particular reports no change at `billing`

#### Scenario: Reported changes keep the paths the differ gave them

- **WHEN** a tracker reports a change under any scope and depth
- **THEN** that change is one the differ reported, with its path unchanged

### Requirement: A change at the tracked object itself is always reported

A change whose path is the tracked object's own root — the type change a sealed tracked type reports
when the two instances are different subclasses — SHALL always be reported, whatever fields the
scope selects and whatever depth it declares.

Such a change cannot be attributed to any one field, and suppressing it would hide the tracked
object being replaced wholesale.

#### Scenario: A subclass swap is reported under a narrow scope

- **WHEN** a tracker for a sealed `Payment` has a scope selecting one field at depth 1
- **AND** the update replaces a `Card` with a `Transfer`
- **THEN** the update reports the type change from `Card` to `Transfer`

### Requirement: A property compared by a hand-written differ tracks like any nested property

A property whose comparison is delegated to a hand-written differ SHALL be tracked by the ordinary
rules for its path. Tracking SHALL NOT require the delegated differ to do anything beyond compare,
and SHALL NOT distinguish it from a generated one.

Tracking therefore needs no escape hatch of its own for a delegated property: the changes such a
property reports carry ordinary paths, and selection and depth apply to them unchanged.

#### Scenario: A change inside a delegated property is tracked by its path

- **WHEN** `Invoice.total: Money` is compared by a hand-written differ and the two totals differ in
  `amount`
- **AND** a scope selects the subtree under `Invoice::total`
- **THEN** the update reports the change at `total.amount`

#### Scenario: A delegated property obeys depth like any nested property

- **WHEN** the same scope tracks every field at depth 1
- **THEN** the update reports no change at `total.amount`

#### Scenario: A compare-only delegated differ is still trackable

- **WHEN** a delegated differ can compare but not apply changes to its type
- **THEN** changes beneath that property are still reported by a tracker whose scope selects them

### Requirement: A per-field callback reports each matching change separately

A tracker SHALL accept a per-field callback that fires once for each matching change, receiving the
change's path, its value before, and its value after.

The two values SHALL be derived from the kind of change as follows, and the mapping SHALL be
exhaustive so that no matching change is silently withheld:

| kind of change            | before          | after          |
|---------------------------|-----------------|----------------|
| a value compared unequal  | the old value   | the new value  |
| a sealed type change      | the old value   | the new value  |
| an element added          | `null`          | the new value  |
| an element removed        | the old value   | `null`         |
| an element moved          | the old index   | the new index  |

Callbacks SHALL fire in the order the differ reported the changes.

#### Scenario: A value change reports both sides

- **WHEN** `reference` changes from `"R1"` to `"R2"` and a per-field callback is registered
- **THEN** the callback fires once with path `reference`, before `"R1"`, and after `"R2"`

#### Scenario: An addition reports a null before

- **WHEN** an element with key `A3` is added to `addresses`
- **THEN** the callback fires with path `addresses[id=A3]`, before `null`, and after the new element

#### Scenario: A removal reports a null after

- **WHEN** the element with key `A1` is removed from `addresses`
- **THEN** the callback fires with path `addresses[id=A1]`, before the old element, and after `null`

#### Scenario: A move reports its two indices

- **WHEN** the element with key `A2` moves from index 0 to index 1 and its contents are unchanged
- **THEN** the callback fires with path `addresses[id=A2]`, before `0`, and after `1`

#### Scenario: Callbacks fire in change order

- **WHEN** an update matches changes at `reference` and then at `status`
- **THEN** the callback fires for `reference` before it fires for `status`

#### Scenario: An update with no matching change fires nothing

- **WHEN** an update's only change is excluded by the scope
- **THEN** the per-field callback does not fire

### Requirement: A batched callback reports one update as a whole

A tracker SHALL accept a batched callback that fires at most once per update, receiving the baseline
instance, the new instance, and every matching change as an ordered list.

A batched callback SHALL NOT fire for an update with no matching change, so a caller can treat a
call as meaning "something I asked about changed".

A tracker SHALL accept per-field callbacks and batched callbacks together, and SHALL accept more than
one of either. Every registered callback SHALL be invoked for an update it matches.

#### Scenario: A batched callback receives both instances and the whole set

- **WHEN** an update from `a` to `b` matches changes at `reference` and `status`
- **THEN** the batched callback fires once with `a`, `b`, and both changes in that order

#### Scenario: A batched callback does not fire for an empty match

- **WHEN** an update matches no change
- **THEN** the batched callback does not fire

#### Scenario: Both callback styles fire for the same update

- **WHEN** a tracker registers one per-field callback and one batched callback
- **AND** an update matches two changes
- **THEN** the per-field callback fires twice and the batched callback fires once

#### Scenario: Several callbacks of the same style all fire

- **WHEN** a tracker registers two batched callbacks and an update matches one change
- **THEN** both callbacks fire

### Requirement: An annotated type's declared scope is used when the caller declares no field

When a tracked type declares a scope on itself — by carrying tracking annotations, so that its
generated declaration exposes one — a tracker over that type SHALL use that scope when the caller
declares no field.

A caller that declares at least one field SHALL replace the declared scope entirely: the fields
named at the call site are the scope, and no field is added back from the type's declaration.

A depth stated at the call site SHALL take precedence over any depth the type declares.

A tracked type that declares no scope SHALL behave as an empty scope: the caller's declaration alone
decides, and a caller that declares nothing tracks the whole object.

#### Scenario: A declared scope is used when the caller declares none

- **WHEN** `Order` declares `reference` as its only tracked field
- **AND** a tracker over `Order` is created with no field
- **AND** the update differs at `reference` and at `status`
- **THEN** the update reports the change at `reference` only

#### Scenario: A caller's fields replace the declared scope

- **WHEN** `Order` declares `reference` as its only tracked field
- **AND** a tracker is created selecting `Order::status` only
- **AND** the update differs at `reference` and at `status`
- **THEN** the update reports the change at `status` only

#### Scenario: A caller's depth overrides the declared depth

- **WHEN** `Order` declares every field tracked at depth 1
- **AND** a tracker is created with no field and depth 2
- **AND** the update differs at `billing.city`
- **THEN** the update reports that change

#### Scenario: A type with no declared scope tracks everything by default

- **WHEN** `Order` declares no tracking scope
- **AND** a tracker over `Order` is created with no field and no depth
- **THEN** every change the differ reports is reported by the tracker

### Requirement: A property excluded from comparison can never be tracked

A property that is excluded from comparison SHALL never be reported by a tracker, whatever the
scope, because it never produces a change to report.

#### Scenario: An ignored property produces no tracked change

- **WHEN** an ignored property differs between the baseline and the new instance and no other
  property differs
- **THEN** the update reports no changes
- **AND** no callback fires

### Requirement: A tracker's report can be applied to its baseline

The changes a tracker reports SHALL be a `Diff` in the same form any differ produces, so that a type
which can have a diff applied to it can have a tracker's report applied to it, with no conversion.

When the scope tracks the whole object at unlimited depth, applying a tracker's report to the
baseline SHALL reproduce the instance the tracker was updated with, exactly as applying an ordinary
diff does.

When the scope is narrowed, applying the report SHALL reproduce the tracked part of that instance and
SHALL leave every untracked property at its baseline value. It SHALL NOT report a failure for a
change the scope excluded: an excluded change was never in the report.

#### Scenario: An unrestricted tracker's report reproduces the target

- **WHEN** a tracker over `Order` tracks the whole object at unlimited depth and is updated from `a`
  to `b`
- **AND** the reported diff is applied to `a`
- **THEN** the result equals `b`
- **AND** no failures are reported

#### Scenario: A narrowed tracker's report propagates only the tracked fields

- **WHEN** a tracker over `Order` selects `Order::reference` only and is updated from `a` to `b`,
  where `a` and `b` differ at both `reference` and `status`
- **AND** the reported diff is applied to `a`
- **THEN** the result has `b`'s `reference`
- **AND** the result has `a`'s `status`
- **AND** no failures are reported

#### Scenario: An empty report applies cleanly

- **WHEN** an update matches no change and the reported diff is applied to the baseline
- **THEN** the result equals the baseline
- **AND** no failures are reported

### Requirement: One declaration carries comparison, application and tracking

For an annotated type, tracking SHALL be reached through the same generated declaration that
compares the type and applies changes to it, so that a single import provides all three. The
declaration's existing name, package, comparison signature and application signature SHALL NOT
change.

A type that is comparable but declares no tracking scope SHALL keep exactly the declaration it has
today.

#### Scenario: All three capabilities are reached through one declaration

- **WHEN** a class is annotated for both comparison and tracking
- **THEN** comparing it, applying changes to it, and reading its declared tracking scope are all
  reached through the same generated declaration
- **AND** one import provides all three

#### Scenario: A comparable type without tracking is unchanged

- **WHEN** a class is annotated for comparison only
- **THEN** its generated declaration exposes comparison and application exactly as before
- **AND** it exposes no tracking scope

### Requirement: Tracking requires no reflection and no dependency beyond the result types

Tracking SHALL be usable with only the annotations and the result types on the runtime classpath. It
SHALL NOT read a type's structure at runtime, and SHALL depend on nothing that a differ does not
already depend on.

Field selection SHALL be resolved from property references without a reflection library, as
hand-written comparison already is.

#### Scenario: A tracking consumer's runtime classpath is unchanged

- **WHEN** a module uses tracking and its build succeeds
- **THEN** its runtime classpath contains the annotations and the result types
- **AND** it contains no reflection library and nothing that performs generation

#### Scenario: A hand-written scope needs no annotations on the classpath

- **WHEN** a module uses only hand-written differs and hand-written scopes
- **THEN** it compiles and tracks without any kdiff annotation being applied to any of its types

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


### Requirement: Every stated depth is validated wherever it is stated

A depth SHALL be at least 1, or the value denoting unlimited depth. Any other depth SHALL be rejected
where it is stated, whether it is stated as a scope's own depth or as the depth of one named field, and
whether the scope is built standalone or declared inline while creating a tracker.

The rejection SHALL state what is allowed and the value that was given, and SHALL name the property
when the depth was stated for one. An annotated class states its depths in source and has them
rejected as compile errors; a hand-written scope states them at runtime and SHALL have them rejected
as it is built, before any tracker can hold it.

This is behaviour the library already has; it is stated here because the two builders now reach it
through one shared declaration rather than two hand-mirrored ones, and the point of that change is
that this requirement cannot come to hold for one route and not the other.

#### Scenario: A field's depth of zero is rejected

- **WHEN** a scope names a field at depth 0
- **THEN** building it fails with an error naming the value 0 and stating that a depth must be at
  least 1 or unlimited

#### Scenario: A negative depth other than unlimited is rejected

- **WHEN** a scope names a field at depth -2
- **THEN** building it fails with an error naming the value -2

#### Scenario: The unlimited depth is accepted for a field

- **WHEN** a scope names a field at the unlimited depth
- **THEN** building it succeeds
- **AND** the field is tracked to any depth, as if named for its whole subtree

#### Scenario: A scope's own invalid depth is rejected

- **WHEN** a scope states its own depth as 0
- **THEN** building it fails with an error naming the value 0

#### Scenario: An inline scope is validated the same way

- **WHEN** a tracker is created with an inline scope naming a field at depth 0
- **THEN** creating it fails with the same error the standalone builder reports

### Requirement: A scope block cannot reach the members of an enclosing builder

A block that declares a tracking scope SHALL NOT expose the members of any builder block enclosing it,
and SHALL NOT expose its own members to a block nested inside it. Calling an enclosing builder's
member from such a block SHALL be a compile error.

Reaching the enclosing builder deliberately SHALL remain possible by naming its receiver explicitly.

Nothing about which changes a correctly-written scope selects SHALL change.

#### Scenario: A differ's member is not reachable from a scope block

- **WHEN** a scope block nested inside a differ block calls a member belonging to the differ builder
- **THEN** the code does not compile

#### Scenario: A callback declaration is not reachable from an inline scope

- **WHEN** an inline scope block calls a member that belongs only to the enclosing tracker builder
- **THEN** the code does not compile

### Requirement: A tracker block runs exactly once, so a caller can initialise a value in it

Every function of the library that takes a tracking or scope block SHALL state that it invokes that
block exactly once, so that a `val` declared outside the block can be assigned inside it and read
after it without the compiler reporting it as possibly uninitialised.

#### Scenario: A value is initialised inside a tracker block

- **WHEN** a caller declares an uninitialised `val`, assigns it inside a tracker or scope block, and
  reads it after the block
- **THEN** the code compiles
