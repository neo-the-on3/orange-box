# Contributing to Orange Box

## Branches and pull requests
- Work on a feature branch and open a pull request; nothing is pushed to `main` directly.
- PRs are rebase-merged once CI (build, lint, unit tests, emulator smoke) is green.
- Keep PRs few: club related work into one PR with one Conventional Commit per concern (rebase merges keep
  the commits). Never squash unrelated work together.

## CI is run by hand (Actions minutes)
- `ci.yml` has no push or pull_request trigger. Develop and test locally first
  (`./gradlew testDebugUnitTest lintDebug`, Robolectric screenshots included), then, just before merging:
  1. rebase the branch onto the latest `main` and force-push it once;
  2. `gh workflow run ci.yml --ref <branch>` and wait for it (`gh run watch`);
  3. rebase-merge only if that run is green, and delete the branch.
- The dispatched run on the rebased branch tests the same tree that lands on `main`, so there is no
  separate push-to-main run. Don't push to the branch again while its run is in progress (a new dispatch
  on the same ref cancels the old one).
- `workflow_dispatch` only works for workflows whose file on the default branch has that trigger; the run
  itself uses the workflow file from `--ref`. `backend.yml` is separate: see Backend CI below.
- The emulator smoke job is the expensive one: add smoke steps only when they are essential and short.
- Reference the parent issue in the PR body (`Part of #28`, `Closes #29`) and add the PR to the project board.

## Commit messages: Conventional Commits
Every commit message, PR title and squash/merge title uses
[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/):

```
type(scope): summary in the imperative, lower case, no trailing period
```

- **Types:** `feat`, `fix`, `docs`, `chore`, `ci`, `build`, `refactor`, `test`, `perf`, `style`.
- **Scopes** (pick the closest): `onboarding`, `obd`, `bt`, `telemetry`, `ui`, `simulator`, `settings`,
  `database`, `backend`, `smoke`, `deps`. The scope may be left out for repo-wide changes.
- Breaking changes: add `!` after the scope (`feat(obd)!: …`) and a `BREAKING CHANGE:` footer.

Examples:

```
feat(onboarding): add permissions, vehicle and dongle setup
fix(telemetry): ignore stale samples in harsh-brake detection
ci(smoke): walk onboarding before the Home checks
```

History from before this rule (up to PR #94) is left as it is.

## Dependencies
- No Google services: no `com.google.android.gms` (Play Services), no Firebase, no Google Maps,
  no Google sign-in. Use platform APIs (`LocationManager`) and keyless maps (osmdroid / MapLibre).
  AndroidX, Material, Hilt/Dagger and Compose are fine.
- No Pokevian/CarooLive artwork, fonts, icons or sounds (see `docs/ui/LEGACY_OBD_UI.md`).

## Local build
```
./gradlew assembleDebug lintDebug testDebugUnitTest --max-workers=2
```

## Backend CI (`backend/`)
The Backend workflow (`.github/workflows/backend.yml`) runs only when started by hand, to save
GitHub Actions minutes. Run `make ci` in `backend/` locally while you work; then, once per PR and just
before merge, rebase the branch onto the latest `main`, push it and run
`gh workflow run backend.yml --ref <branch>`. Merge only when that run is green and Backend Tester
has signed off. Details: [`backend/README.md`](backend/README.md#ci).
