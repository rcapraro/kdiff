## Purpose

Defines what a caller gets when they ask to be told about changes rather than to compute them: the
baseline a tracker keeps, how the scope of what is worth reporting is declared — by hand or by
annotation — what depth means, what each callback receives for each kind of change, and how a
tracker's output composes with applying a diff.

## ADDED Requirements

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

### Requirement: A tracking scope with no selectors tracks the whole object

A scope that declares no field and inherits no declared scope SHALL track every compared property at
unlimited depth: every change the differ reports SHALL be reported by the tracker.

#### Scenario: An empty scope reports every change

- **WHEN** a tracker is created with a scope that declares no field and no depth
- **AND** the update differs at `reference`, at `billing.city`, and at `addresses[id=A2].street`
- **THEN** the update reports all three changes
- **AND** each change keeps the path the differ gave it

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
