---
name: sync-upstream
description: Use when syncing this fork's main from upstream mobile-dev-inc/Maestro — "sync from upstream", "pull from upstream", "merge upstream", "update the fork", "we're behind upstream", or any variant of bringing upstream changes into the fork.
---

# Sync the fork from upstream

Merge `upstream/main` into this fork, resolving the small set of conflicts that are real and skipping the large set that are artifacts of how past syncs were recorded.

## When to use

Any request to bring upstream changes into the fork. Not for syncing a feature branch off `main`, and not for the release flow (see the `release` skill).

## The one thing that makes this repo different

**Past syncs landed as squash commits.** A squash records no upstream parent, so git's merge base stays pinned at the last *real* merge. Every upstream change the squash already absorbed then replays as a phantom conflict, indistinguishable from a real one.

On the 2026-09-08 sync to v2.10.0 this was the difference between **41 conflicts and 5**. Resolving the phantoms by hand risks reverting upstream work or clobbering fork customizations, and it buries the handful that actually need a decision.

So: **find the real sync point first. Never merge straight from the recorded merge base.**

## Procedure

### 1. Assess

```bash
git fetch upstream
git rev-list --left-right --count main...upstream/main      # ahead / behind
git merge-base main upstream/main                           # possibly stale
git log main --format='%h %ad %s' --date=short | grep -iE 'sync|upstream' | head
```

If the most recent sync commit has **one parent** (`git cat-file -p <sha> | grep -c '^parent'` returns 1), it was a squash and the merge base is stale. Continue to step 2. If it is a real merge, skip to step 3.

### 2. Find the real sync point and graft it

Score upstream commits from the squash's time window by how few files differ; the true match stands out sharply.

```bash
SQUASH=<squash sha>
for c in $(git log --format=%h --first-parent --since=<~1wk before> --until=<~1wk after> upstream/main); do
  echo "$(git diff --name-only $SQUASH $c | wc -l) $c $(git log -1 --format='%ad %s' --date=short $c)"
done | sort -n | head -8
```

Graft the winner as the squash's second parent, then confirm the conflict count drops:

```bash
git replace --graft $SQUASH $(git rev-parse $SQUASH^) <winner>
git merge-tree --write-tree --name-only main upstream/main | head -40
```

Try the top two or three candidates and keep the one with the fewest conflicts.

> **The graft only shapes the merge computation.** Delete it with `git replace -d $SQUASH` **before committing** so the commit records correct real parents. Verify with `git replace -l` (must be empty).

### 3. Branch and merge

```bash
git checkout main && git checkout -b sync_from_upstream_<YYYY-MM>
git merge --no-commit --no-ff upstream/main
git status --short | grep -E '^(UU|DU|UD|AA|AU|UA|DD)'
```

### 4. Resolve conflicts

Work each one against **four** references, not two: the merged file, `main:<path>`, `upstream/main:<path>`, and the graft base `<winner>:<path>`. The graft base tells you which side actually changed.

Recurring shapes and how they were resolved on 2026-09-08:

| Conflict | Resolution |
|---|---|
| `CHANGELOG.md` | Keep the fork's `## Unreleased` entries, insert upstream's new version sections above the fork's last version heading |
| Fork feature vs. upstream refactor of the same function | Keep the fork's behavior, express it through upstream's new types. Check whether a helper the fork calls still exists upstream |
| Upstream converts string dispatch to an enum | Add the fork's values to upstream's enum rather than keeping the string branch |
| Binary driver artifacts | Almost always take upstream's; see step 5 |

### 5. Check for squash damage

A partial squash **drops upstream files silently** rather than conflicting, so this step is not optional.

Known casualties, both dropped by past squashes:

```bash
# iOS simulator driver artifacts — deleted by BOTH the May and July 2026 squashes (aef75343 fixed the first)
git checkout upstream/main -- maestro-ios-driver/src/main/resources/driver-iPhoneSimulator/
# lockfile and node version — regenerated/staled instead of taken from upstream
git checkout upstream/main -- maestro-cli/mcp-viewer/package-lock.json .nvmrc
```

For the general case, look for upstream lines added during the squash window that the merged tree lacks. For each file that still differs from upstream, diff `<previous merge base>..<graft base>` and check whether its added lines survive in `git show :<path>`. Investigate every hit; some will be legitimate fork changes.

### 6. Verify no fork customization was lost

Mechanical, and worth doing every time. Against the graft base:

- Fork-**added** files must all still exist.
- Fork-**deleted** files must still be absent, unless deliberately restored in step 5.
- Fork-**added** lines must still be present, except ones you rewrote knowingly in step 4.
- Fork-**deleted** lines must not reappear. Compare occurrence counts against `main:<path>`, not mere presence, or identical lines elsewhere in the file produce false hits.

Also scan the pre-squash window the same way. A line missing there was usually superseded by a later fork commit; confirm with `git log -S'<line>' main -- <path>` rather than assuming.

### 7. Hunt semantic conflicts

These compile-break or misbehave without ever conflicting textually. The highest-yield check:

**Members the fork added to shared interfaces, versus types upstream added.** The fork added `yamlString()` to the `Command` interface. Upstream's four new dark-mode commands did not implement it, and the build failed. Enumerate what the fork added to `Command`, `Condition`, `MaestroCommand` and friends, then check every class upstream added that implements them.

Also worth a look: fork code calling helpers upstream deleted, fork YAML fields versus upstream's schema generation and its tests, and fork command paths running against upstream's changed engine or listener lifecycle.

### 8. Build and test

```bash
./gradlew :maestro-cli:installDist -x :maestro-ios-driver:buildIosDriver \
  -x :maestro-android:copyMaestroAndroid -x :maestro-android:copyMaestroServer
```

> **Exclude those three tasks.** They regenerate the driver APKs and iOS zips into the source tree and will overwrite the artifacts you just restored. If you forget, `git status` shows modified binaries; restore with `git checkout -- <paths>` before continuing.

```bash
MAESTRO_CLI_AI_KEY=dummy-for-tests ./gradlew \
  :maestro-orchestra-models:test :maestro-orchestra:test :maestro-cli:test \
  :maestro-client:test :maestro-test:test :maestro-ios:test \
  :maestro-ios-driver:test :maestro-utils:test --continue \
  -x :maestro-ios-driver:buildIosDriver -x :maestro-android:copyMaestroAndroid \
  -x :maestro-android:copyMaestroServer
```

> **The AI key is required.** `Orchestra` builds an AI client in its constructor, so without it every Orchestra-based test dies with a bare `NullPointerException` at `Orchestra.initAI` that reads like merge damage. The value is never validated; any non-empty string works.

Confirm counts from the XML reports rather than trusting the console, and confirm the working tree still matches the index afterward.

### 9. Document

`CLAUDE.md` requires a README Fork Changelog entry for every change. Add one under `### Sync with Upstream` recording the upstream version and sha, the conflicts and how each was resolved, and any squash damage repaired. State the commit count from the **graft base**, not the stale merge base.

### 10. Hand off

**Stop here and report.** Do not commit — this repo's owner commits their own work. Summarize the conflicts, the resolutions, the verification results, and anything left unresolved.

If asked to open the PR afterward:

```bash
gh pr create --repo omio-ui-platform/Maestro --base main --head <branch> ...
```

> **Always pass `--repo omio-ui-platform/Maestro`.** In this checkout `gh` resolves to `mobile-dev-inc/Maestro`, the upstream project. Without the flag, `gh pr list` returns empty for fork PRs and `gh pr create` aims a fork-internal branch at upstream.

## Pre-handoff checklist

- [ ] `git replace -l` is empty
- [ ] No unmerged paths; no conflict markers in any staged file
- [ ] iOS simulator driver artifacts present and matching upstream
- [ ] Working tree matches the index after the build
- [ ] Build passes; tests pass with the AI key set
- [ ] Fork-survival check clean, with every exception explained
- [ ] README Fork Changelog entry added
- [ ] Nothing committed
