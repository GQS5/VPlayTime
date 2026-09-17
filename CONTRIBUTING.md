# Contributing to VPlayTime

Thanks for helping out. Small, focused PRs beat big rewrites.

## Ground rules

- **Don't change gameplay defaults silently.** Reward amounts, slots, requirements, permissions, commands and menu layout in `src/main/resources/` are production behavior — any change there needs an explicit reason in the PR.
- One concern per PR. Keep diffs reviewable.
- `./gradlew clean build` must pass (286 tests, no server needed). Add tests for new validation, providers, executors and reload behavior following the existing package-mirrored layout.
- Follow `docs/DEVELOPMENT.md` (records, `ConfigError` with file+path, fail-closed, Folia-safe threading, no PAPI types outside `papi/` behind `PapiGuard`).

## Bug reports

Use the bug-report template: version, Paper/Folia build, Java, config snippet (redact names/IPs), **full console lines** (our errors carry file+path — paste them verbatim), and what you expected. Check `docs/CONFIGURATION.md` first — most "reload ignored my edit" cases are answered there.

## Pull requests

- Fill in the PR template. Link the issue if one exists.
- Update `CHANGELOG.md` under `[Unreleased]` and any affected `docs/` page.
- Never commit: server folders, world data, databases (`*.db`), logs, credentials, tokens, personal paths. `.gitignore` covers the usual suspects — keep it that way.

## Releases

Maintainers only: releases are cut from GitHub Releases (`vX.Y.Z`), built and tested by `release.yml`. Never attach a hand-built JAR.
