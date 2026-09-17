# Security policy

## Supported versions

| Version | Supported |
|---|---|
| 1.8.x (latest) | Yes |
| < 1.8.0 | No — please update |

## Reporting a vulnerability

**Do not open a public issue.** Email the maintainers (see the repository owner profile) with:

- what you found and which version/commit,
- steps to reproduce or a proof of concept,
- what you think the impact is (duplication, data loss, privilege escalation, …).

You should get a first response within 7 days. We will coordinate a fix and credit you in the release notes unless you prefer otherwise.

## Scope notes

- Reward commands run as **console** — treat `rewards.yml` as privileged configuration: only trusted admins should edit it, and review command lines like you would server scripts.
- Claim state lives in SQLite (`plugins/VPlaytime/data/data.db`); back it up before manual edits.
- This project has no network code and stores no credentials.
