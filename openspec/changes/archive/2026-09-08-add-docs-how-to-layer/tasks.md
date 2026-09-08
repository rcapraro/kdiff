## 1. Establish the source material

The error page quotes text verbatim (design D2), and a paraphrase there is a defect. Collect it from
the code before writing prose, so no sentence is written from memory.

- [x] 1.1 Transcribe all thirteen compile-time diagnostic strings from
  `kdiff-processor/src/main/kotlin/io/github/kdiff/processor/DiffProcessor.kt`, and verify each against
  the assertion that covers it in `kdiff-processor`'s `DiagnosticSpec` — every transcribed message must
  appear in a passing test, and any diagnostic with no test is recorded as a finding rather than
  quoted as if verified.
- [x] 1.2 Transcribe the fourteen `describe()` sentences and the fourteen `Reason` KDoc meanings from
  `kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/Patcher.kt`, and verify the count is fourteen
  and that each maps to exactly one of design D3's five causes with none left over.
- [x] 1.3 Transcribe the message construction for `DuplicateDiffKeyException`, `CyclicStructureException`
  and `PatchFailedException` from `kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/Errors.kt`, and
  the two construction-time `IllegalArgumentException` messages (an empty `differ { }`, an invalid
  depth) from their sources, verifying each against the runtime spec that asserts it.
- [x] 1.4 Confirm no error text collected above already appears verbatim in `docs/` or `README.md`, so
  the new page is the first place any of it is written and design D5's sole-owner rule holds from the
  start.

### Group 1 findings

Transcribed to `error-transcription.md` in the session scratchpad. Three corrections to counts this
change's own artifacts asserted, and five messages with no test:

- **Fifteen compile-time diagnostics, not thirteen.** `docs/annotations.md` merges the two depth
  sites and the two "requires `@Trackable`" sites into one row each, and omits two messages entirely:
  `@DiffWith needs a differ class`, and the `elements of` variant of "kdiff cannot compare". The
  proposal and design say thirteen because they counted that table's rows. `docs/errors.md` documents
  fifteen.
- **Six construction-time messages, not two.** The scope rule "names the properties it tracks or the
  properties it excludes, never both" and both depth variants were uncounted — and a code review
  afterwards caught two more that this inventory missed entirely, because it swept `Dsl.kt` and
  `TrackScope.kt` but not `Route.kt`: `<property> is named by more than one handler` and `a routing
  declares one otherwise handler; this is the second`. Both are reachable from public API, and
  neither appeared anywhere in the documentation. A re-sweep of every `require`/`check`/`throw` in
  `kdiff-runtime/src/main` confirms six is now the complete count. **Lesson for the next inventory:
  enumerate the mechanism (`require`), not the files you expect to hold it.**
- **`PatchFailureCaseSpec` already covers all fourteen reasons**, so tier 3a is fully test-backed and
  `docs/errors.md` can cite it.
- **No test asserts six of the message texts:** the two untabulated diagnostics above, `a differ
  compares something: …`, both `depth must be at least 1, or UNLIMITED_DEPTH; was <n>` variants
  (the *processor's* depth message is asserted; the runtime's is not), and `a routing declares one
  otherwise handler; this is the second`. Recorded rather than quoted as verified, per 1.1. Not fixed
  here — adding assertions is a test change to `kdiff-runtime` and `kdiff-processor`, outside this
  change's stated scope of `kdiff-sample`'s test source set.
- **Several messages that *are* asserted are asserted only as substrings**, or against the same
  constant the production code uses (`UNSUPPORTED_TARGET`), so rewording their tails also fails no
  test. `CONTRIBUTING.md` says this plainly rather than claiming a guard that does not exist.

## 2. Recipes, as tests first

Every recipe cites a compiled file, so the test must exist and pass before the page citing it is
written — otherwise `DocumentationSamplesSpec` fails on a citation to a file that has no such lines.

- [x] 2.1 Create `kdiff-sample/src/test/kotlin/demo/RecipesSpec.kt` with the recipes that need new code,
  one Kotest test each, test names reading as sentences, reusing the existing `demo` fixtures and
  adding no type to `Model.kt`; verify `./gradlew :kdiff-sample:test --tests '*RecipesSpec*'` passes.
- [x] 2.2 Within that spec, cover: auditing every change from one diff; requiring a patch to be total
  with `getOrThrow` and catching `PatchFailedException`; branching on a `PatchFailure.Reason` by cause;
  telling a `Moved` apart from an addition plus a removal; narrowing a diff to one property with `at`
  and `under`. Verify each is an assertion, not a `println`.
- [x] 2.3 Attempt the "compare a value by something other than `equals`" recipe as a hand-written
  `object : Differ<T>` over `compareValue`. Verify by test if it reduces to code a reader would write;
  per design D1's gate, if it does not, drop the recipe and record that the FAQ answers the question by
  pointing at `docs/hand-written.md`'s escape hatch instead.
- [x] 2.4 Identify which recipes are already backed by a cited file — `kdiff-tutorial`'s
  `UpdatePersonHandler.kt`, `kdiff-sample`'s `Model.kt`, `OrderTrackingSpec.kt` — and verify each
  intended citation's distinctive lines exist in that file today, so those recipes cite the existing
  source rather than duplicating it into `RecipesSpec`.
- [x] 2.5 Run `./gradlew :kdiff-sample:test` and verify the whole module is green, including the
  existing specs, before any page cites the new one.

### Group 2 findings

- **2.3's gated recipe ships.** Comparing an amount numerically does reduce to code a reader would
  write: a `Differ<Money>` that adds a `ValueChanged` itself when `BigDecimal.compareTo` disagrees,
  and calls `compareValue` for the rest. It reports the values the model holds rather than the
  normalised ones, which is precisely why the DSL cannot express it. The FAQ fallback is not needed.
- **The five-cause grouping is compiler-checked.** `RecipesSpec`'s `Reason.cause()` is an exhaustive
  `when` over all fourteen cases, so a fifteenth case fails to compile there rather than going
  undocumented in `docs/errors.md`. This was not planned; it falls out of writing the recipe as a
  test and is a stronger guarantee than the page could carry on its own.
- Seven tests, all passing, `:kdiff-sample:test` green.

## 3. The three new content pages

- [x] 3.1 Write `docs/errors.md` in design D2's three tiers, with design D3's five-cause grouping for
  the fourteen reasons, every message quoted verbatim from group 1, and every entry pointing at the
  page owning the concept behind it. Verify all fourteen reason names, all three exception types and
  all thirteen diagnostics are present, and that no Kotlin block is unmarked.
- [x] 3.2 Write `docs/how-to.md` as task-named recipes, each the smallest working code plus one link
  onward, citing group 2's files with `<!-- from: -->`. Verify it states no rule of its own — every
  rule it relies on is a link — and that `./gradlew :kdiff-sample:test --tests '*DocumentationSamples*'`
  passes with the new citations.
- [x] 3.3 Write `docs/faq.md`, each answer short plus one link, held to design D6: backed by a test or
  by an already-documented rule cited to its page. Verify no answer introduces a rule documented
  nowhere else, and that the three unsettled questions (Android, serialising a `Diff`, cost on a given
  model) either state only what the build or the types guarantee, or are absent.
- [x] 3.4 Give all three pages the "Where to go next" footer the existing seven carry, and verify each
  new page is reachable from at least one existing page and not only from the index.

## 4. Edits to existing pages

Confined to links, footers and the index move, so this group's diff stays small and uniform (design's
review risk). No rationale prose is relocated, condensed or deleted.

- [x] 4.1 Create `docs/README.md` holding the documentation index — the existing seven rows plus the
  three new pages — and verify GitHub renders it at `docs/` by checking the file is named `README.md`
  at the directory root.
- [x] 4.2 Replace `README.md`'s seven-row Documentation table with the short pointer of design D4, and
  verify no per-page row remains on the front page and that the pointer reaches `docs/README.md`.
- [x] 4.3 Update every in-repository link to the old index anchor, and verify by searching the
  repository for `README.md#documentation` that none remains.
- [x] 4.4 Add the `docs/errors.md` pointer to `docs/patching.md`'s "What produces a failure" section
  and to `docs/annotations.md`'s rejection table, stating in each that the error page is the complete
  list; verify the three reason names `docs/patching.md` keeps are still the only ones it names, per
  design D5's bounded overlap.
- [x] 4.5 Extend the "Where to go next" footers on the seven existing pages to reach `how-to.md`,
  `errors.md` and `faq.md` where relevant, and verify no footer gained an entry unrelated to its page.
- [x] 4.6 Re-read `docs/diffing.md`, `docs/tracking.md`, `docs/hand-written.md` and
  `docs/architecture.md` and verify their diffs contain only link and footer changes — no paragraph of
  rationale moved or removed.

### Group 4 findings

- **4.3 required no edits.** Nothing in the repository linked `README.md#documentation`; the only
  occurrences are this change's own artifacts describing the risk. The design's "moving the index
  breaks inbound links" risk did not materialise.
- **4.6 verified by diff rather than by reading alone:** the seven existing pages show 18 insertions
  and **0 deletions**, so no rationale prose could have been moved or condensed. The only non-footer
  additions are the two link-out paragraphs 4.4 asked for.
- **Overlap stayed bounded.** `docs/patching.md` names exactly the three reason cases design D5
  allowed — `UnpatchableProperty`, `NotConstructorProperty`, `NoElementForKey` — and no more.

## 5. Keep the conventions true

- [x] 5.1 Update `CONTRIBUTING.md` to say that a how-to recipe is backed by a test in `RecipesSpec` and
  cited with `<!-- from: -->` rather than written as an `illustrative` block, and that `docs/errors.md`
  quotes message text verbatim from the source. Verify the page still describes the marker convention
  and version guard correctly alongside the addition.
- [x] 5.2 Verify `CLAUDE.md` needs no edit: confirm it makes no statement about the documentation index
  or the page set that this change falsifies, and record the check rather than editing the file
  reflexively.
- [x] 5.3 Verify no `build.gradle.kts`, no `gradle/libs.versions.toml` entry and no `src/main` file in
  any module was touched, by reviewing `git status` and `git diff --stat` against the proposal's Impact
  section.

### Group 5 findings

- **5.2: `CLAUDE.md` needed no edit.** Its only reference to a documentation page is
  `docs/tutorial.md` as the worked example behind `kdiff-tutorial`, which is still true. It makes no
  statement about the index or the page set. Checked, not edited.
- **5.3 verified by `git diff --numstat`:** deletions occur in exactly one file, `README.md` (9), which
  is the replaced index table. No `build.gradle.kts`, no `gradle/libs.versions.toml`, no `src/main`
  file in any module appears in the working tree at all.
- **Observation, out of scope:** `CONTRIBUTING.md` documents `./gradlew dokkaGenerate` — so a Dokka
  reference build exists, which the archived `improve-documentation` proposal recorded as a gap it was
  leaving open. Nothing on the new pages claims otherwise, but `docs/README.md` does not mention the
  generated API reference either. Worth its own change if that output is published anywhere.

## 6. Verification

- [x] 6.1 Verify the completeness claim that motivated the change: search `docs/` for each of the
  fourteen `PatchFailure.Reason` names and confirm all fourteen now appear, where eleven appeared
  nowhere before.
- [x] 6.2 Verify the searchability claim: pick three diagnostics and one failure sentence at random,
  search `docs/` for their verbatim text, and confirm each is found.
- [x] 6.3 Read `docs/how-to.md` and `docs/faq.md` end to end as a reader who has not read the reference
  pages, and verify every recipe is followable and every answer complete without leaving the page for
  anything but a deliberate link.
- [x] 6.4 Run `./gradlew check` and verify it is green — including `DocumentationSamplesSpec` over the
  four new pages, ktlint, detekt and ABI validation. Report any failure verbatim.

### Group 6 findings

- **6.1: 14/14 reason names now appear in `docs/`**, against 3 before this change.
- **6.2: eight verbatim strings sampled across all four tiers, all found** by a plain text search of
  `docs/`. Before this change, none of them appeared anywhere.
- **6.3 found one real defect and fixed it.** The `cause()` snippet on `docs/errors.md` used a `Cause`
  enum it never showed, so a reader copying it could not compile it. The cited block now opens with
  the enum declaration — which is in `RecipesSpec` already, so the citation still verifies.
  A scripted check also confirms every `###` question on the FAQ and every recipe on the how-to page
  carries at least one link onward; only the bare `##` group dividers do not, which is correct.
- **6.4 `./gradlew check` BUILD SUCCESSFUL.** `DocumentationSamplesSpec` 140 tests / 0 failures with
  the four new pages under it; `RecipesSpec` 7 tests / 0 failures; ktlint, detekt and ABI validation
  green. ABI validation was unaffected, as expected — no published module changed.
### Post-review corrections

A `/code-review` pass after 6.4 found five defects, all fixed and re-verified:

1. **`docs/errors.md` was incomplete** — tier 2 omitted `Route.kt`'s two messages. Both added, the
   tier retitled "Building a differ, a scope or a routing", and the count corrected to six in the
   diagram and the prose. This was a real failure of the change's central claim, since the page's own
   subtitle is "Every message kdiff produces".
2. **`CONTRIBUTING.md` overclaimed the guard** — it said rewording a message fails a test in the same
   change. It does not, for six messages outright and for several more that are asserted only as
   substrings. Rewritten to say plainly that nothing checks the page, and that the discipline is to
   search `docs/errors.md` when rewording.
3. **`CONTRIBUTING.md`'s source list omitted `Dsl.kt`, `TrackScope.kt` and `Route.kt`**, which is
   where every tier-2 message actually lives. All six files now named, grouped by tier.
4. **A FAQ entry described a symptom its cause cannot produce.** "Why did my list report an addition
   and a removal instead of a move?" — an unkeyed reorder reports a value change per position, never
   an addition and a removal, as this change's own `RecipesSpec` proves. Split into two entries: one
   for a reorder not reporting a move (which now states what it *does* report), and one naming the
   three shapes that genuinely report add-and-remove — a `Set`, a keyed list whose key value changed,
   and a `Map` entry whose key changed.
5. **`docs/how-to.md` showed `trackedDiff` as a bare call**, but it is an extension on `Differ<T>` —
   so the one page whose purpose is copyable code had a line that does not compile. Replaced with a
   cited block from `UpdatePersonHandler.kt`, which makes it harness-checked rather than prose.

Findings 1 and 4 are the ones worth remembering: a completeness claim is only as good as the sweep
behind it, and a question is as capable of being wrong as an answer.

- **An anchor checker was written for this change** but deliberately not added to the build: it lives
  in the session scratchpad, not the repository. Every relative `#anchor` link across `README.md`,
  `CONTRIBUTING.md` and `docs/` resolves to a real heading. Making that a permanent gate is a build
  change and belongs to its own change — recorded here as the obvious follow-up, since the new pages
  add 40-odd cross-links and nothing in `check` verifies one.

### Schema task rules recorded as inapplicable

`openspec/config.yaml` requires every processor change to ship a kctfork compile-testing spec, every
runtime change a Kotest unit spec, and a task to update `kdiff-sample` when generated output changes.
No processor or runtime source is touched and no generated output changes, so none applies. The
`kdiff-sample` rule is satisfied incidentally rather than by exception: group 2 adds a spec to that
module and group 6 runs it.
