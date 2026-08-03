---
name: review-loop
description: Iterated multi-agent verification of the whole app. Each round fans out Opus subagents over every aspect of the codebase, adversarially reviews their findings in the main loop, fixes only the findings that survive, and repeats until a round fixes nothing (or 10 rounds). Invoke for "review the whole app", "verification loop", or /review-loop.
---

# Review loop

Repeated wide verification of the app, converging when a round finds nothing
real. This is `code-review` scaled out: that skill's definition of a finding,
its evidence rules and its mutation discipline all apply — read it first.

## The loop

Up to **10 iterations**. Stop early the first time an iteration ends with
**zero fixes applied**.

Each iteration:

1. **Fan out.** Launch subagents (`model: opus`, told to reason hard) in
   parallel, one per aspect, covering the whole app between them:
   - the resume / status-check path (`Resume`, `NovelCheck`,
     `checkStatus`/`checkStatusFrom`, resume-point recording);
   - `DownloadEngine.run()` — listing collection, rename, dedupe, fetch/retry,
     the destructive passes and their gates;
   - `DownloadStore` + `Schema` + migrations — epochs, caches, transactions;
   - the Activities — lifecycle races, wrong-thread DB reads, intent extras,
     stale-snapshot bugs;
   - the services — `DownloadService` queue, translate resolution,
     `CompressService`, `TtsService` handoffs;
   - sites + parsing (`Site` impls, `Listing`, `Renumber`, `Folder`, `Zips`)
     judged against the captured pages.

   On iterations after the first, weight the fan-out toward code changed by
   earlier iterations plus any aspect that produced a confirmed finding; an
   aspect twice clean in a row may be dropped.

2. **Subagent contract.** Each agent reports findings only — no edits. A
   finding needs `file:line`, a concrete minimal trigger sequence, and what a
   real user loses (chapters lost or wrong, money re-spent through the
   translator, the user's own files touched, unrecoverable migration loss, a
   stuck state). Style, naming, taste, and hypotheticals with no trigger are
   explicitly out of scope. Tell them the comments in this repo describe
   intent and have been wrong — the code is the truth.

3. **Adversarial review, in the main loop.** For every finding, read the real
   code and try to KILL it: does the trigger actually reach the line, does a
   guard upstream already stop it, does the claimed loss actually occur?
   Default to refuted when uncertain. Findings that sound convincing are often
   wrong; a fix applied to a refuted finding has created bugs here before.
   Only findings that survive this pass get fixed.

4. **Fix and prove.** Each fix gets a test that fails without it, proved by
   mutation where the logic is pure (break the fix, watch the test fail).
   Type-check with the kotlinc harness (`CLAUDE.md`, "You can type-check the
   Kotlin locally") and run the JUnit suite with the site resources on the
   classpath before counting the fix.

5. **Count.** Fixes applied this iteration > 0 → next iteration. Zero → the
   loop is done.

## Finishing

Commit per iteration (or as one series), push, open the PR **ready** and let
auto-merge take it — never merge by hand. Report per iteration: agents run,
findings raised, findings surviving adversarial review, fixes applied. Then the
usual: check the android workflow on main after merge and report the published
`versionName`.
