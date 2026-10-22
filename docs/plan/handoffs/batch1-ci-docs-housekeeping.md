# Batch 1: CI and docs housekeeping

- **Repo:** `qualified-opinion/Manager`
- **Branch:** `fix/ci-docs-housekeeping`, from `main` `e1f18bd` (or newer `main` once the docs PR merges)
- **Engine:** Codex CLI builds; Claude Code reviews.
- **Source items:** N-10 (issue #32), K-3, offline-check test gap. See [the Claude review](../reviews/claude-review-2026-10-18.md) and [the Codex review](../reviews/codex-review-2026-10-22.md).
- **Size target:** about 250 to 400 changed lines.
- **Protected components:** none touched.

## Goal
Make CI, the release job and the README tell the truth, before v1.2.0 is built.

## Scope
1. `.github/workflows/android-ci.yml`: move `actions/checkout`, `actions/setup-java` and `actions/upload-artifact` to their current majors that run on Node 24, and `android-actions/setup-android` to its current major if one runs on Node 24 (otherwise leave it and say so in the PR). Apply this to all three jobs. Leave `softprops/action-gh-release` alone unless its current major is a drop-in.
2. K-3: in the `signed-release-artifact` job, stop requiring `KEY_ALIAS`. When it is empty, pass nothing so `app/build.gradle.kts` falls back to `upload`, matching `README.md`. Do the same in the tag `release` job if it has the same check. The other three secrets stay required.
3. Offline-check fixtures: add a small shell test (for example `.github/scripts/test-check-apk-offline.sh`) that runs `check-apk-offline.sh` against fixtures and proves it fails on a missing APK and on an APK or manifest dump that declares `android.permission.INTERNET`, and passes on a clean one. Run it as a CI step before the build. Use whatever fixture approach works without network access in the app; generating a tiny APK in CI is fine.
4. `README.md`: fix the clipboard wording. Say it clears after 10 s and on a manual lock, keeps the copy when the lock comes from leaving the app (on purpose, so you can paste), and that the background wipe can run late on an idle phone. Don't promise more than the code does. Say "flagged as sensitive" rather than "hidden by every keyboard".
5. `REVIEW.md`: add one line at the top saying it is a historical note from 9 Oct 2026, and point to `docs/plan/ROADMAP.md` for current status. Don't rewrite the rest.
6. `docs/plan/ROADMAP.md`: set B1 to "In review" with the PR number.

## Out of scope
- Any Kotlin source change, manifest change or dependency change.
- `.aab` or Play bundle work (N-9).
- Clipboard behaviour fixes (that is B2).
- Version bump or release.

## Acceptance criteria
- CI on the PR is green: `./gradlew testDebugUnitTest`, `./gradlew lintDebug lintRelease`, `./gradlew assembleDebug assembleRelease`, then the offline check.
- The new offline-check test fails if you temporarily break `check-apk-offline.sh` (say how you proved this in the PR body).
- No Node 20 deprecation warnings left for the upgraded actions in the CI log, or the PR lists which remain and why.
- The `signed-release-artifact` job's secret check passes with `KEY_ALIAS` empty in a dry reading of the script (show the changed lines in the PR body). Don't run the job.
- `git diff --stat` shows only `.github/**`, `README.md`, `REVIEW.md` and `docs/plan/ROADMAP.md`.

## Risks
- A new action major changes inputs. Read each action's release notes and keep inputs the same.
- Fixture APKs bloat the repo. Prefer generating them in the test or keep them under a few KB.

## Process
Open one PR. Update the ROADMAP status table. Don't merge; wait for Kiln's review and Jan's OK.
