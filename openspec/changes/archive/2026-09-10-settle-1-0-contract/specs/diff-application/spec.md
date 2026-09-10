## MODIFIED Requirements

### Requirement: A change that cannot be applied is reported, never silently dropped

Applying SHALL return the patched instance together with every change it could not apply, each
carrying the change itself and the reason it failed.

A reason SHALL be inspectable, not merely readable. The reasons SHALL form a declared set of cases a
caller can branch on, closed to implementation from outside the library, and each case SHALL carry the
facts it knows as properties — the property name, the key, the index, whichever the case is about — so
that a caller can branch on why a change did not apply, and can act on which property or element it
concerned, without matching prose.

The set SHALL be open to growth between versions: a release that teaches the library to apply a shape
it previously refused MAY declare a further reason, and doing so SHALL NOT be a breaking change. A
caller branching on reasons SHALL therefore be able to state what it does with a reason it does not
recognise, and the library SHALL document that a branch on reasons is written with a catch-all.

Rendering a failure SHALL remain total across that growth: every reason, including one declared after
a caller was written, SHALL render as one human-readable line naming its path and stating its reason in
prose. A caller that only reports failures SHALL therefore be unaffected by a new case.

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

- **WHEN** a caller branches on the reason of a reported failure
- **THEN** the case it matches exposes the property, key or index its reason concerns
- **AND** no prose is matched to reach it

#### Scenario: A reason no caller recognises still reaches that caller

- **WHEN** a caller branches on the reasons it knows and states what it does with any other
- **THEN** a failure carrying a reason declared after that caller was written reaches the catch-all
- **AND** the caller compiles unchanged against the release that declared it

#### Scenario: A failure still renders as a readable line

- **WHEN** a reported failure is rendered as text
- **THEN** the line names the change's path and states the reason in prose
- **AND** this holds for every declared reason, so rendering needs no branch of the caller's own

#### Scenario: Nothing outside the library declares a reason

- **WHEN** code outside the library attempts to declare a further reason
- **THEN** it does not compile

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
