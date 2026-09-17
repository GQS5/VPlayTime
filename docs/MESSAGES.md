# Messages

All text VPlayTime sends lives in `messages.yml` — no player-facing sentence is hardcoded. Every key below names the exact situation that sends it; empty string (`""`) silences that situation (claim feedback then becomes sounds-only).

Format: MiniMessage (`<green>`, `<red>`, …; legacy `&` codes are converted at load). `%word%` tokens are placeholders filled at send time; tokens without a value are removed, values are escaped, so nothing raw or broken ever reaches chat.

## General (`general:`)

| Key | Sent when | Placeholders |
|---|---|---|
| `usage` | Malformed command input (bad subcommand/args) | — |
| `no-permission` | Missing `vplaytime.*` permission | — |
| `players-only` | Console runs `/vplaytime` or `/vplaytime <menu>` | — |
| `unknown-menu` | `/vplaytime <menu>` names nothing | `%menu%` = requested id |
| `unknown-player` | `info`/`reset` can't resolve the name/UUID | `%player%` = requested input |
| `no-data` | Resolved player has no VPlayTime data | `%player%` = looked-up label |

Plus top-level `prefix` (prepended to everything) and `loading` (data still loading, open rejected).

## Claims (`claim:`)

| Key | Sent when | Placeholders |
|---|---|---|
| `success` | Grant completed | — |
| `locked` | Clicked a locked reward | `%required_playtime%` = formatted requirement |
| `already-claimed` | Clicked an owned reward | — |
| `failed` | Grant failed, rolled back, retry is safe | — |
| `unavailable` | Reward auto-suspended after repeated command failures; attempts refused without granting until `/vplaytime reload` | — |

## Admin (`admin:`)

| Key | Sent when | Placeholders |
|---|---|---|
| `reload-success` | `/vplaytime reload` committed | — |
| `reload-failed` | Reload rejected (old config kept) | — |
| `reload-detail` | Appended after `reload-failed` | `%detail%` = file, path, reason |
| `info-header` | `/vplaytime info` header | `%player%` = label |
| `info-uuid` | info body | `%uuid%` |
| `info-playtime-online` | info body (online) | `%playtime%` effective, `%stored%` banked |
| `info-playtime-offline` | info body (offline) | `%playtime%` = stored |
| `info-claims-none` | info body, no claims | `%total%` = reward count |
| `info-claims` | info body | `%claimed%`, `%total%`, `%list%` = ids |
| `info-provider` | info body | `%provider%` = active source id |
| `info-reward-system` | info body | `%status%` = ENABLED/DISABLED |
| `info-validated` | info body | `%validated%` = VALID count |
| `info-invalid` | info body | `%invalid%` = INVALID count |
| `info-unverifiable` | info body | `%unverifiable%` = UNVERIFIABLE count |
| `info-active-config` | info body | `%status%` = VALID/NONE |
| `info-disabled-reason` | info body, only while disabled | `%reason%` = why no valid plan is active |
| `reset-done` | `reset` succeeded | `%reward%`, `%player%`, `%note%` |
| `reset-failed` | `reset`/`resetall` failed | `%player%`, `%detail%` |
| `resetall-done` | `resetall` succeeded | `%detail%`, `%player%` |

## GUI (`gui:`, silent by default)

| Key | Sent when | Placeholders |
|---|---|---|
| `menu-opened` | Any menu opens (commands, page turns) | `%menu%` = menu id |
| `menu-closed` | Any menu closes | `%menu%` = menu id |
| `reward-error-name` | Error-state item title while no valid plan is active | — |
| `reward-error-lore` | Error-state item lore (list) | — |

## Deliberately not messaged

- **Retry prompts** — covered by `claim.failed` ("try again"); a separate key would never fire distinctly.
- **Unknown reward/level clicks** — unreachable from the GUI (clicks resolve against the live map; unknown ids only occur via the API, which returns `NOT_FOUND` to the caller).
- **Page-turn/first/last notices** — `next-page`/`previous-page` resolve to plain opens at load, so there is no runtime page-turn event; `menu-opened` fires instead.
- **Provider changed/unavailable as chat** — provider problems surface as `reload-failed` + reason and console diagnostics; availability has no player-safe value to display beyond the fail-closed `0`.
- **Info data rows** beyond the templates above — values, not sentences; they stay code-built inside the documented templates.
