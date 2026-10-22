# VaultLock roadmap

Last updated: 22 Oct 2026, at `main` `e1f18bd` (CI green). Owner: Jan (GitHub `qualified-opinion`). Planner: Kiln. Executor: Nightshift.

## What VaultLock is
A private, 100% offline Android password manager and TOTP authenticator (`com.janreins.vaultlock`). No internet permission, no network libraries, no sync. Jan sideloads signed APKs from GitHub Releases. Latest release is **v1.1.0** (15 Oct 2026, tag at `f17a267`, signed with Jan's Qualified Opinion key). `versionCode = 2`, `versionName = "1.1.0"`.

## Status table

Done work before this roadmap (summary): Phase 1 review fixes F-01 to F-23 landed in #13 to #20 (see `REVIEW-2026-10.md`), and items 1 to 7 of the 18 Oct Claude plan (tracking issue #22) landed in #34, #21 and #35 to #38.

| Batch | Branch | Scope | Status | Pack |
| --- | --- | --- | --- | --- |
| Claude plan 1+2 | (merged) | N-1 offline check runs; N-2 test memory and backup IO | Merged #34, `367f3ee` | - |
| Claude plan 3 | (merged) | Manual signed APK artifact job on `main` | Merged #21, `09bb431` | - |
| Claude plan 4 | (merged) | N-3 "Immediately" auto-lock also locks after 30 s idle | Merged #35, `ca9412c` | - |
| Claude plan 5 | (merged) | N-4 keep copied password when Back clears the ViewModel | Merged #36, `188c6f8` | - |
| Claude plan 6 | (merged) | N-5 no keyboard learning in username, URL, notes | Merged #37, `1cc7fcd` | - |
| Claude plan 7 | (merged) | N-6 don't repeat a finished lockout after restart | Merged #38, `e1f18bd` | - |
| Docs | `docs/plan-roadmap` | This roadmap, reviews, packs, AGENTS.md, CLAUDE.md | Merged #39, `11401ed` | - |
| Release v1.2.0 | (no branch) | Signed APK of everything since v1.1.0; Jan publishes | After B1 and B2 merge, then Jan's phone checks | - |
| B1 | `fix/ci-docs-housekeeping` | N-10, K-3, offline-check fixtures (issue #32) | In review, PR #TBD | [batch1](handoffs/batch1-ci-docs-housekeeping.md) |
| B2 | `fix/clipboard-wipe-reliability` | K-1, K-2 clipboard wipe | Planned | [batch2](handoffs/batch2-clipboard-wipe.md) |
| B3 | `fix/generator-honest-strength` | F-19, F-20 (issue #31, part 1) | Outline | - |
| B4 | `feat/passphrase-engine` | Passphrase engine plus bundled wordlist (issue #31, part 2) | Outline | - |
| B5 | `feat/passphrase-ui` | Passphrase UI and generator state (issue #31, part 3) | Outline | - |
| B6 | `fix/idle-timer-uptime` | N-8 idle timer on uptime clock (issue #30). **Protected** | Outline | - |
| B7 | `fix/keep-entry-dates` | N-7 keep entry dates on master password change (issue #29). **Protected** | Outline | - |

## Item index
Old ids are kept. F-ids are from `REVIEW-2026-10.md`, N-ids from [the Claude review](reviews/claude-review-2026-10-18.md), K-ids from [the Codex review](reviews/codex-review-2026-10-22.md).

| Id | Priority | Item | Status |
| --- | --- | --- | --- |
| F-01 to F-12, F-14 to F-17, F-21 to F-23 | - | Phase 1 review findings | Fixed in #13 to #20, or parked on purpose (F-14) |
| F-03 | - | v1 migration copies plaintext titles | Closed: no v1 vault exists (Jan, 22 Oct 2026). Doc note only |
| F-13, F-18 | - | Field AAD / save conflict detection | Deferred, see cut list |
| F-19 | P2 | Generator sampling bias | B3 |
| F-20 | P2 | Optimistic strength labels (`Qwertyuiop123456!` is "Very strong") | B3 |
| N-1 to N-6 | - | Claude plan items 1 to 7 | Merged |
| N-7 | P3 | Master password change resets every entry's `updatedAt` | B7 |
| N-8 | P3 | Idle timer uses wall clock | B6 |
| N-9 | P2 | No `.aab` bundle (Play only) | Cut unless D3 |
| N-10 | P3 | README clipboard wording, stale `REVIEW.md`, Node 20 actions | B1 |
| K-1 | P2 | Late inexact alarm can skip clipboard wipe for good | B2 |
| K-2 | P2 | A fresh ViewModel can cancel the previous instance's wipe alarm (reachable via #36 Back path) | B2 |
| K-3 | P3 | Manual signed job requires `KEY_ALIAS`, docs say it defaults to `upload`. Secret is set, so it works today | B1 |
| Passphrase mode | P2 | No passphrase generator | B4, B5 |

## Decisions Jan still owes (each has a default)
- **D1. When to cut v1.2.0.** Decided by Jan on 22 Oct 2026: no release until K-1, K-2 and K-3 are fixed, so v1.2.0 comes after B1 and B2 merge.
- **D2. Passphrase defaults.** Default: EFF large wordlist, 6 words, `-` separator, no forced digit or capital. Jan can change these in B5 review.
- **D3. Google Play.** Default: no; APK sideload only, so N-9 and issue #33 stay cut.
- **D4. Protected batches B6 and B7.** Each needs Jan's explicit go for that batch before work starts. Default: run them last, one at a time.

## Later ideas (not scheduled)
- Signed `.aab` build if D3 changes.
- Instrumented (device) tests for clipboard, Back and process death.

## Cut list
- F-03 migration work (Codex batches 8 and 9): no v1 vault exists.
- F-13 versioned authenticated field envelope and F-18 save snapshot conflicts (Codex batches 10 and 11): format change with no current risk, and a single-user, single-window app.
- N-9 Play bundle and issue #33, unless D3 changes.
- Claude plan PR 13 (encrypt legacy titles): same reason as F-03.

## How work is run
1. Kiln writes a handoff pack and posts it in the Coding room.
2. Nightshift posts its execution plan. Jan (directly, or via Atlas as "from Jan via Atlas") says go.
3. Nightshift builds on the pack's branch with the pack's engine (default Codex CLI), runs the CI commands from `AGENTS.md`, and opens one PR that also updates the status table above.
4. Kiln reviews with a different engine (Claude Code for Codex-written PRs) against the pack's acceptance criteria and posts approve or changes needed, naming the head sha.
5. After approval, green CI and Jan's OK, Nightshift squash-merges pinned to the approved head. Any push after approval needs a re-check.
6. Releases: Jan asks, the `signed-release-artifact` job (manual run on `main`) builds `VaultLock.apk` plus `.sha256`, the signing cert is checked against Jan's key, and Jan bumps the version and publishes himself. Agents never tag or create releases.

## Open verification (Jan's phone checks)
- [ ] Auto-lock "Immediately": leave the vault open and idle; it locks in about 30 to 35 s (#35).
- [ ] Auto-lock 30 s while typing a long note: it does not lock mid-typing (F-10, #19).
- [ ] Copy a password, press Back, switch to a browser and paste: it pastes, then the clipboard clears after about 10 s (#36, #16).
- [ ] Keyboard does not suggest words from notes, usernames or URLs in other apps (#37).
- [ ] After a wrong-password lockout ends, a restart does not make you wait again (#38).
- [ ] Install v1.2.0 over v1.1.0 in place with no data loss (same signing key).

## First message for an agent starting alone
> You are working on VaultLock (`qualified-opinion/Manager`), an offline Android password manager. Read `AGENTS.md` and `docs/plan/ROADMAP.md`. Pick the first batch in the status table whose status is Planned and whose pack exists in `docs/plan/handoffs/`. Follow that pack exactly: its branch, scope and acceptance criteria. Never add network access, never touch protected components unless the pack names them and Jan has said go, never push to `main`, tag or release. Open one PR that also updates the status table, then stop and wait for review.

## Facts a new owner needs
- `main` is not branch-protected; the process gate is review approval plus Jan's OK.
- CI workflow: `.github/workflows/android-ci.yml`. Jobs: tests, lint, debug and release builds, offline APK check; `signed-release-artifact` (manual, `main` only); `release` (on `v*` tags, Jan only).
- Signing secrets in the repo: `RELEASE_KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`.
- Jan's release key: `CN=Qualified Opinion, OU=Personal, O=Personal, L=Cebu City, ST=Cebu, C=PH`, cert SHA-256 `f7bd9187bc9cfdcbb6fca190b1b52866cffa14ad37617e3c35308faeb8c936ab`. Every release must be signed with it so updates install in place.
- History: `REVIEW.md` (9 Oct repair, historical), `REVIEW-2026-10.md` (Phase 1, F-ids), `docs/plan/reviews/` (newer reviews).
