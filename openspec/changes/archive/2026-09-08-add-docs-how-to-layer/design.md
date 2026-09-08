## Context

See proposal.md — Why. Four constraints shape the approach rather than the motivation.

**`DocumentationSamplesSpec` already discovers new pages.** It reads
`listOf(README.md) + repoRoot.resolve("docs").listFiles()`, filtered to `.md`. Every page this change
adds is therefore checked from the moment it exists: its `<!-- from: -->` blocks are verified against
their cited files, its coordinates against `kdiff.version`, and any unmarked Kotlin block fails the
"marks every Kotlin sample" test. **No change to the spec, and no change to any `build.gradle.kts`,
is required for the new pages to be covered.** That is what makes a test-backed recipes page cheap.

**Seven pages already carry a "Where to go next" footer.** The previous documentation change added
them precisely so no page is a dead end. A new page reachable only from the index would violate the
convention the last change established.

**This repository treats duplication as a defect.** `CLAUDE.md` states rules and
`docs/architecture.md` explains them; the proposal for the previous documentation change moved general
rules *out* of the tutorial and into the reference page that owns the behaviour. Adding three pages to
a set of seven is the point at which ownership has to be stated explicitly, or the same rule will end
up written four times and corrected in one.

**The fourteen `PatchFailure.Reason` cases are not fourteen independent facts.** Read against their
KDoc, they fall into five causes, and the cause is what determines what a reader does next. A
fourteen-row table would be complete and still not answer "so what do I change?".

## Goals / Non-Goals

**Goals:**

- A reader who knows what they want to accomplish reaches working code without reading a concept page.
- A reader holding an error message finds it by searching the documentation for its text.
- The `PatchFailure.Reason` vocabulary is documented at full size, grouped so the grouping does the
  explaining.
- Every added sample is compiled and asserted, so the new pages cannot rot in the way the previous
  change was written to stop.
- Exactly one index, and exactly one owner for every documented rule.

**Non-Goals:**

- **Rewriting the reference pages.** Their prose is accurate and well-illustrated. This change adds a
  layer above them and edits them only where they must link out or must stop being the sole index.
- **Relocating rationale.** Named in the proposal; repeated here because it is the boundary most
  likely to erode while writing. The instruction "avoid too much implementation detail" is satisfied
  by giving the working reader a route that never passes through the rationale, not by deleting it.
- **A page per recipe, or a page per error.** Three new content pages, not thirty. A recipe that needs
  its own page is a sign it belongs in the reference.
- **Prose about anything not established.** See D6.

## Decisions

### D1 — Recipes are tests, in one new spec, cited by marker

`kdiff-sample/src/test/kotlin/demo/RecipesSpec.kt`, one Kotest test per recipe that needs new code,
each cited from `docs/how-to.md` with `<!-- from: kdiff-sample/src/test/kotlin/demo/RecipesSpec.kt -->`.

*Why not `illustrative` blocks:* that is the exact hole the previous documentation change was written
to close — "every line a new reader copies first … is marked `illustrative` and therefore checked
against nothing", and it drifted at the first release. A how-to page is the page a reader copies from
most, so it is the last page that should be unchecked.

*Why not a new `kdiff-recipes` module:* a module means `settings.gradle.kts`, a `build.gradle.kts`, a
KSP plugin application and a new node in the module graph that `docs/architecture.md` documents — a
build change, which this project's rules make its own change. `kdiff-sample` is already "the
integration test and the source every documentation sample is checked against", and already receives
`kdiff.repoRoot` and `kdiff.version`. One file, no wiring.

*Why one spec rather than a spec per theme:* the recipes share fixtures with the existing `demo` specs
and are read as a set. A second `demo` spec per theme would fragment the fixtures without making any
recipe clearer.

**A recipe whose test cannot be written is dropped, not written as prose.** The recipe list below is
the intent; the test is the gate. This matters most for "compare a value by something other than
`equals`", which the `differ { }` vocabulary does not express directly — it needs a hand-written
`object : Differ<T>` over `compareValue`, which `docs/hand-written.md` documents as the escape hatch.
If that recipe does not reduce to something a reader would actually write, it is dropped and the FAQ
answers the question instead by pointing at the escape hatch.

Recipes already backed by a cited file are cited there rather than duplicated into `RecipesSpec`:
`kdiff-tutorial`'s `UpdatePersonHandler.kt` for events from a diff, `kdiff-sample`'s `Model.kt` for
`WeightDiffer` and `MoneyDiffer`, `OrderTrackingSpec.kt` for the narrowed scope and the hand-written
scope.

### D2 — One error page, three tiers, verbatim text

`docs/errors.md`, organised by *when the reader meets the error*, because that is what they know when
they arrive:

```
   +-------------------------------------------------------------------+
   | TIER 1   COMPILE TIME -- the build fails, KSP names a declaration |
   |          13 diagnostics. Verbatim text + what to change.          |
   +-------------------------------------------------------------------+
   | TIER 2   CONSTRUCTION -- building a differ or scope throws        |
   |          IllegalArgumentException x2: an empty differ { },        |
   |          an invalid depth. Before any comparison runs.            |
   +-------------------------------------------------------------------+
   | TIER 3a  RUNTIME, RECOVERABLE -- apply() reports and carries on   |
   |          14 PatchFailure.Reason cases, grouped by cause (D3).     |
   |          You still have a usable value.                           |
   +-------------------------------------------------------------------+
   | TIER 3b  RUNTIME, REFUSED -- there is no result to return         |
   |          DuplicateDiffKeyException, CyclicStructureException,     |
   |          PatchFailedException. Declared types, catchable.         |
   +-------------------------------------------------------------------+
```

*Why the message text verbatim:* it is the only thing the reader has. The paraphrases in
`docs/annotations.md` ("names it and what `@Diffable` accepts") describe the message to someone who
has already understood it. Verbatim text makes the page reachable by search — from a browser, from
`grep`, from an IDE.

*Why one page and not a section on each owning page:* the tiers cut across the existing pages. A
compile diagnostic about `@DiffWith` concerns `annotations.md`, `hand-written.md` and `patching.md` at
once, and a reader with a red build does not know which. One destination, with each entry pointing at
the page that explains the concept behind it.

*Why not three pages:* the reader arrives once, with one message.

*The text must be read from the source, not from memory.* `DiffProcessor.kt` holds the diagnostic
strings and `kdiff-processor`'s `DiagnosticSpec` asserts them; `describe()` in `Patcher.kt` holds the
failure sentences; `Errors.kt` holds the exception messages. Any quoted text is copied from those
files. This is the one page where a paraphrase is a defect.

### D3 — The fourteen reasons are documented as five causes

Grouped from their KDoc in `Patcher.kt`, with each group carrying the one thing a reader can act on:

| Cause | Cases | What it means | What to do |
|---|---|---|---|
| The type cannot rebuild that property | `NotConstructorProperty`, `UnpatchableProperty` | reconstruction goes through `copy`, or through a differ that only compares | change the declaration: make it a constructor parameter, or give the `@DiffWith` object a `Patcher` |
| The change names nothing compared | `UnknownProperty` | the path does not exist on this type | the change is stale, hand-built, or from another type |
| The change's shape does not fit the property's | `NotApplicableToValue`, `NotApplicableToKeyedList`, `NotApplicableToPositionalList`, `NotApplicableToMap` | the path addresses a property in a way its comparison shape cannot express | the change was not produced by diffing this type |
| The target is not there to patch | `NoElementForKey`, `NoElementAtIndex`, `NoEntryForKey`, `NothingBeneathNull` | the key, index or entry the change names is absent from the source | the diff is being applied to a source it did not come from |
| The container's elements have no patchable identity | `ElementComparedAsValue`, `EntryComparedAsValue`, `SetElementNotModifiable` | elements are compared as opaque values, or by set membership | give the element type a `@DiffKey`, or accept add-and-remove |

Two of the five are declaration problems the reader fixes in their model; three are almost always a
diff meeting the wrong source. That distinction is the page's value and no per-case row carries it.

*Why grouped rather than a flat table:* a flat table is a restatement of the sealed interface, which
the reader can already read. *Why not grouped by which `patch*` helper raises it:* that is
implementation, and the reader has a failure, not a stack.

Every case is still named individually, with its `describe()` sentence, so the page is searchable by
either.

### D4 — `docs/README.md` is the index; `README.md` points at it

The previous change ruled out `docs/index.md` on the grounds that the README's table is the index and
two indexes would need keeping in step. That reason is sound and is met by **moving** the index rather
than adding one: `docs/README.md` (which GitHub renders at `docs/`) holds the table, and `README.md`
keeps a short pointer — start here, how do I, reference — with no per-page rows to fall out of date.

*Why move at all:* the table goes from seven rows to ten on a front page that also carries install,
quickstart, five code samples and status. The index is the part of the README a reader uses after
they have decided to adopt, which is exactly when they are no longer on the front page.

*Alternative considered — keep all ten rows in `README.md` and add no `docs/README.md`.* Rejected:
`docs/` renders as a bare file listing on GitHub, so a reader who navigates into the directory gets
`annotations.md, architecture.md, diffing.md…` alphabetically, with no route in. That is the state
today and adding three more files makes it worse.

*Alternative considered — both, with `docs/README.md` generated or duplicated.* Rejected on the
previous change's own reasoning.

### D5 — Ownership is stated, so nothing is written twice

```
   docs/README.md        index. links only, no rules, no samples.
   docs/how-to.md        task -> smallest working code + one link onward.
                         states no rule; every rule it needs, it links to.
   docs/errors.md        SOLE owner of the full error catalogue: every
                         verbatim message, all 14 reasons, all 3 exceptions.
   docs/faq.md           question -> short answer + one link. introduces
                         no rule that is not already documented elsewhere.
   ---------------------------------------------------------------------
   diffing.md            the rules. UNCHANGED but for links out.
   patching.md           the rules. UNCHANGED but for links out.
   tracking.md           "
   hand-written.md       "
   annotations.md        "
   architecture.md       the why. UNCHANGED.
   tutorial.md           the narrative. UNCHANGED.
```

The new pages **point**; they do not restate. The one accepted overlap is in `docs/patching.md`: its
"What produces a failure" section keeps the three cases that carry the concept, with the cited test
that asserts `UnpatchableProperty("weight")`, and gains a line saying `docs/errors.md` is the complete
list. Deleting those three would cost a real test citation and leave the concept unexplained; the
overlap is three names, and `errors.md` is stated to be the catalogue so there is no question which
page is authoritative.

### D6 — Nothing is asserted that is not established

Every recipe is backed by a passing test (D1). Every FAQ answer is either backed by a test or is a
restatement of a rule already documented, cited to the page that documents it. Where neither holds,
**the question is dropped rather than answered**.

This is a live constraint, not a formality. Three questions a reader plausibly asks are not settled by
anything in this repository:

- *Does it work on Android?* Nothing states it. The build guarantees JDK 21 and a JVM target of 21
  (`README.md` says why: the builder entry points are `inline`). The FAQ may state that guarantee; it
  may not extrapolate from it to a platform nothing tests.
- *Can a `Diff` be serialised?* `Change` carries `Any?` values, so the answer depends on the payload.
  The FAQ says what the type holds and stops, unless a test settles it.
- *What does it cost on my model?* `docs/architecture.md` already declines to quote a figure, and
  `kdiff-benchmarks` is not published. The FAQ points at that section and quotes nothing.

Where the code does not settle a question, the page says what is known and names the limit. An
inaccurate FAQ is worse than an absent one, because an FAQ is read as a ruling.

### D7 — Footers reach the new pages

The three new content pages gain the "Where to go next" footer the other seven carry, and the seven
existing footers gain the new pages where relevant — `patching.md` and `annotations.md` to
`errors.md`, the reference pages to `how-to.md`. Without this the new pages are reachable only from
the index, which is the dead-end problem the previous change fixed.

### Schema design rules recorded as inapplicable

`openspec/config.yaml` requires a design to cover type resolution (nullability, generics, collections,
maps, enums, nested `@Diffable` types), incremental processing and aggregation, a sample of the
generated file, and the runtime-versus-generated split. All four are rules for a change to the
processor. This change adds no annotation, resolves no type, generates no file and moves no logic
between runtime and generated code — `kdiff-processor` is not touched, and no `src/main` file in any
module is. There is nothing to cover, and inventing coverage would put false statements in the record.

## Risks / Trade-offs

**A how-to page duplicates the reference and the two drift.** → D5 states ownership per page and
requires the how-to to link rather than restate; the one accepted overlap is named and bounded to
three type names. The reference pages are also the ones with cited tests, so a drift between them and
the code fails the build on the reference side first.

**Verbatim error text goes stale when a message is reworded.** The message text is the one thing on
the new pages that `DocumentationSamplesSpec` cannot check — it verifies fenced *Kotlin* blocks
against cited files, and a diagnostic string in a Markdown table is neither. → Accepted, with two
mitigations: the text is copied from `DiffProcessor.kt`, `Patcher.kt` and `Errors.kt` rather than
written, and `kdiff-processor`'s `DiagnosticSpec` already asserts the diagnostic strings, so a reworded
message fails a test that a contributor is looking at when they reword it. Extending the sample check
to arbitrary quoted strings is a plausible follow-up and is not attempted here: it needs a marker
convention for prose, which is a design of its own.

**The reason grouping is an editorial judgement that could mislead.** A reader who trusts "the diff is
being applied to a source it did not come from" and is actually hitting a genuine model problem is
worse off than with a flat list. → Every case keeps its own name and its own `describe()` sentence
alongside the group, so the grouping guides without replacing the specifics, and the group headings say
what the cases have in common rather than diagnosing the reader's situation.

**Moving the index breaks inbound links.** Anything linking to `README.md#documentation` stops
resolving. → Only this repository links there, and the change updates those links. No published
artifact embeds the anchor, and no release notes reference it.

**"Add more samples" becomes a rewrite of `kdiff-sample`.** A recipes spec that grows fixtures and
model types starts competing with `OrderDiffSpec` for ownership of what the sample module
demonstrates. → `RecipesSpec` adds no type to `Model.kt` and no `src/main` file anywhere; the proposal
states this and the tasks re-check it. A recipe needing a new model type is a signal the recipe is
really a reference gap.

**The change is large and mostly prose, so review degrades to skimming.** → Tasks are grouped so the
three new pages, the edits to existing pages, and the new spec can each be read on their own, and the
edits to existing pages are deliberately confined to links and footers so that group's diff is small
and uniform.

## Migration Plan

None. Documentation has no deployment step and no consumer to migrate. No build file changes, so
nothing to roll back beyond deleting the added files and reverting the link edits.

The change is safe to land in any order relative to a release, with one caveat inherited from the
previous change: any coordinate written on a new page must name the currently published version, or
`DocumentationSamplesSpec` fails and names the mismatch. The new pages have no reason to carry a
coordinate — installation stays in `README.md` — so the simplest compliance is to write none.

## Open Questions

None that affect the approach or the task breakdown. The one genuine unknown — whether the
"compare by something other than `equals`" recipe reduces to code a reader would write — is resolved
by D1's gate during apply: the test either passes and the recipe ships, or the recipe is dropped and
the FAQ points at the escape hatch. Either outcome is complete; neither changes the page set.
