---
name: simplify
description: Cleanup-only review of recently changed code — reuse, duplication, redundant guards, dead branches, over-general code with one caller. Applies the fixes. Does NOT hunt for bugs; use code-review for that. Invoke when the user asks to simplify, clean up, deduplicate, or reduce complexity.
---

# Simplify

Reduce complexity **without changing behaviour**. This is the cleanup path. It
does not look for bugs — `code-review` owns that.

## Why this repo needs its own version

This app deletes and renames the user's files and spends real money on the
Anthropic Batches API. It has been through a long run of defect-audit rounds,
and nearly every round's worst finding was a defect inside the *previous*
round's fix. So the guards here are load-bearing far more often than they look,
and a "simplification" that drops one is a defect that costs somebody their
library.

Two real examples of what that looks like:

- A guard that appears dead because the feature it was written for was removed,
  but is still reached by a different cause. The folder rename was deleted in
  #350; four guards around it looked orphaned and every one turned out live
  (#351). Comments citing a removed cause were the actual bug — they invited
  deletion of something necessary.
- A comment that reads as redundant restatement but is the only record of a
  shipped bug. The house style deliberately explains *why*, at length. Cutting
  that is not simplification.

## What to look for

1. **Duplicated logic** — the same rule expressed twice. This is the highest
   value find, because copies drift. Two were found this way: `dirNameOrGuess`
   and `ownedDirNames` carried the same fallback chain verbatim (#352), and
   `novelDir` carried a third.
2. **Redundant guards** — two checks where one strictly implies the other.
   *Prove the implication.* Do not guess.
3. **Dead branches** — a condition no current caller can satisfy. Check git
   history for why it was added before concluding it is dead.
4. **Over-general code with one caller** — a parameter always passed the same
   value, an interface with one implementation, a lambda always `{ true }`.
5. **Genuinely redundant comment restatement** — never the reasoning itself.

## Rules

- Every proposal must preserve behaviour. State exactly why it is unchanged,
  and what would break if you were wrong.
- No taste-driven changes. Only demonstrable redundancy.
- Ground everything in real code with `file:line`.
- **Verify by running.** Apply the change, compile, run the full suite. If a
  test fails, the simplification was wrong — say so rather than proposing it.
- "Nothing worth simplifying" is a valid and useful result.

## Build and test

Gradle will not run here. Compile with the kotlinc harness — see `CLAUDE.md`
("You can type-check the Kotlin locally"). Run tests with
`org.junit.runner.JUnitCore` and `android/app/src/test/resources` on the
classpath. Check the current test count first and make sure you reproduce it
before trusting any result; a partial harness once produced a false result.
`node worker.test.mjs` runs the Worker guards.

## Finishing

Follow the merge workflow in `CLAUDE.md`: push the branch, open the PR ready
(not draft), squash-merge immediately, then check the android workflow on main
and report the published `versionName`.
