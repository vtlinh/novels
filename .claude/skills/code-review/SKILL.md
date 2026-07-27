---
name: code-review
description: Review the current diff (or a named area) for correctness bugs that would cost a user data or money. Reports findings; does not clean up — use simplify for that. Takes an optional effort level, e.g. "code-review high". Invoke when the user asks for a code review, an audit, or a check for bugs.
---

# Code review

Find **correctness bugs**. Cleanup and deduplication belong to `simplify`.

Default scope is the current diff against `main`. A named area in the arguments
overrides that. An effort word — `low`, `medium`, `high`, `max` — sets how wide
to cast; default `medium`.

## What counts as a finding here

Only things that cost a real user something:

- chapters lost, or silently holding the wrong text;
- a novel re-downloaded or re-translated — the translator spends real money per
  chapter through the Batches API;
- the user's own files touched (the tree may be a shared folder they keep other
  things in);
- a migration that drops data — those run once per install on the only copy the
  user has, and `names` is a paid glossary that cannot be rebuilt;
- a state the app can never leave.

Style, naming and taste are out of scope.

## Rules that make this useful rather than noise

1. **Ground every claim in the real code.** Quote `file:line`. Comments in this
   repo describe intent and have repeatedly been wrong — the code is the truth.
2. **A finding needs a concrete minimal trigger.** State the exact sequence that
   reaches it and what the user loses. No trigger, no finding.
3. **Measure claims about site HTML.** 227 real captured pages live under
   `android/app/src/test/resources/pages/`, one HTML per zip, with
   `manifest.tsv`/`chapters.tsv` measured independently of the app's parser.
   Read them with python + zipfile. The fixtures are one page per distinct
   novel, so they cannot show duplicate-title collisions; harvest the sidebar
   link graph inside the pages for that.
4. **Look hardest at the newest code.** The most severe finding has usually been
   inside the previous change's fix.
5. **Refute before fixing.** Findings that sound convincing are often wrong. Run
   an adversarial pass that tries to kill each one and defaults to refuted when
   uncertain. Two examples of why: one proposed fix would have *created* the bug
   it claimed to fix, and a guard that looked like a regression turned out to
   prevent 40 wrong chapter positions in the state that actually recurs.
6. **A test that cannot fail is a finding.** Establish it by mutation — break
   the production code the way the test claims to cover, recompile, check the
   test fails. No mutation, no claim. Five vacuous tests have been found this
   way, including ones that had passed review for months.
7. **"Nothing found" is a valid result.** Say it plainly rather than padding.

## Reporting

Use `ReportFindings` when it is available, ranked most-severe first, and do not
also print the findings as prose. Otherwise list them ranked, each with trigger,
cost, and evidence. Say explicitly which sub-areas you checked and found clean,
so the next pass need not redo them.

## Build and test

Gradle will not run here. Compile with the kotlinc harness — see `CLAUDE.md`
("You can type-check the Kotlin locally"). Run tests with
`org.junit.runner.JUnitCore` and `android/app/src/test/resources` on the
classpath. Reproduce the current test count before trusting any mutation result.
`node worker.test.mjs` runs the Worker guards offline.

Note the local check cannot catch a missing resource in `res/` — that fails at
link time in CI.

## Finishing

Any fix gets a test that fails without it, proved by mutation. Then the merge
workflow in `CLAUDE.md`: push, open the PR ready (not draft), squash-merge
immediately, check the android workflow on main, and report the published
`versionName`.
