# Contributing to Orange Box

## Branches and pull requests
- Work on a feature branch and open a pull request; nothing is pushed to `main` directly.
- PRs are rebase-merged once CI (build, lint, unit tests, emulator smoke) is green.
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
