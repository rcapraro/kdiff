## MODIFIED Requirements

### Requirement: A change that cannot be applied is reported, never silently dropped

Applying SHALL return the patched instance together with every change it could not apply, each
carrying the change itself and the reason it failed.

A reason SHALL be inspectable, not merely readable. The reasons SHALL form a closed, declared set of
cases a caller can handle exhaustively, and each case SHALL carry the facts it knows as properties —
the property name, the key, the index, whichever the case is about — so that a caller can branch on
why a change did not apply, and can act on which property or element it concerned, without matching
prose. A failure SHALL still render as one human-readable line naming its path and its reason, so
existing logging output is unchanged in substance.

The result SHALL make a clean application distinguishable from a partial one, so a caller that
requires strictness can detect any failure. Changes that can be applied SHALL still be applied when
others fail; a single unapplicable change SHALL NOT discard the rest.

A change SHALL be reported as a failure when its path names no property the type compares, when it
falls beneath a property the library can compare but not reconstruct, or when it targets a property
that is not a constructor parameter.

#### Scenario: An unknown path is reported

- **WHEN** a change whose path names no compared property is applied
- **THEN** the result reports that change as a failure with a reason
- **AND** the returned instance is otherwise patched

#### Scenario: A reason is handled by case rather than by message

- **WHEN** a caller matches exhaustively on the reason of a reported failure
- **THEN** every reason the library can report is covered without a catch-all branch
- **AND** the matched case exposes the property, key or index its reason concerns

#### Scenario: A failure still renders as a readable line

- **WHEN** a reported failure is rendered as text
- **THEN** the line names the change's path and states the reason in prose

#### Scenario: Other changes still apply when one fails

- **WHEN** a list of changes contains one unapplicable change and several applicable ones
- **THEN** the applicable ones are applied
- **AND** only the unapplicable one is reported

#### Scenario: A clean application is distinguishable from a partial one

- **WHEN** every change applies successfully
- **THEN** the result reports no failures

#### Scenario: A property that is not a constructor parameter is reported

- **WHEN** a change targets a property declared in the class body rather than the constructor
- **THEN** it is reported as a failure naming that property
- **AND** the reason states that only constructor properties can be reconstructed
- **AND** the reason's case identifies it as a non-constructor property and carries the property name

## ADDED Requirements

### Requirement: A caller can require a patch to be all-or-nothing

Applying SHALL keep returning a partial result with its failures, because that is what lets a caller
salvage what applied. A caller that instead wants a patch to succeed completely or not at all SHALL be
able to say so at the call site, on the result, without inspecting the failures itself.

Asking for the value that way SHALL return the patched instance when every change applied, and SHALL
raise a declared exception of the library's own otherwise. That exception SHALL carry every failure, so
a caller that catches it is no less informed than one that inspected the result.

#### Scenario: A clean result yields its value

- **WHEN** a caller requires the value of a result in which every change applied
- **THEN** the patched instance is returned

#### Scenario: A partial result raises instead

- **WHEN** a caller requires the value of a result reporting at least one failure
- **THEN** the library raises its declared patch-failed exception
- **AND** the exception carries every reported failure
- **AND** the exception's message states how many changes failed

#### Scenario: Requiring the value does not change what applying does

- **WHEN** the same changes are applied twice, once read as a partial result and once required
  outright, to a source for which they all apply
- **THEN** both produce equal instances

### Requirement: Applying refuses the inputs comparison refuses

Where comparison refuses an input rather than describing it, applying SHALL refuse the same input the
same way, raising the same declared exception type — so a caller need not learn two vocabularies for
one malformed model.

This SHALL cover a keyed list holding two or more elements that share a key, and a descent that
exceeds the bound. In both cases applying SHALL raise rather than return a result: the source cannot
be rebuilt at all, so there is no partial value to hand back.

Applying descends along the paths of the changes it is given and stops wherever the source runs out,
so neither a cyclic source nor a change list deeper than the bound is unbounded on its own. Together
they are — the source never ends and the changes keep asking for more — and that is the case the
bound refuses.

#### Scenario: A duplicate key is refused when applying

- **WHEN** a diff is applied to a source whose keyed list holds two elements sharing a key
- **THEN** the declared duplicate-key exception is raised, carrying the list property, the key
  property and the duplicated key value
- **AND** no result is returned

#### Scenario: A cyclic source is refused once the changes reach into it

- **WHEN** a diff whose change paths run deeper than the bound is applied to a source whose graph
  contains a cycle
- **THEN** the declared cyclic-structure exception is raised, naming the path at which the descent
  stopped, and reporting that an instance was re-entered
- **AND** no `StackOverflowError` is raised

#### Scenario: A deep change list stops where the source ends

- **WHEN** a change whose path runs deeper than the bound is applied to a finite source
- **THEN** no exception is raised
- **AND** the change is reported as a failure for having nothing beneath a null property

#### Scenario: Applying is unchanged for a source within the bound

- **WHEN** any source that stays within the descent bound is patched before and after this change
- **THEN** the same instance is produced and the same failures are reported
