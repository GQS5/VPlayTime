# Placeholders

Three distinct families — don't mix them up.

## 1. VPlayTime's own expansion (needs PlaceholderAPI)

Registered once at enable when PlaceholderAPI is installed: identifier `vplaytime`, persistent across PAPI reloads. Values always come from the **active** provider, so they follow `/vplaytime reload` provider switches immediately.

| Placeholder | Meaning |
|---|---|
| `%vplaytime_seconds%` | Provider playtime in seconds |
| `%vplaytime_minutes%` | Provider playtime in whole minutes |
| `%vplaytime_hours%` | Provider playtime in whole hours |

Unknown parameters return null (left untouched). Unavailable provider values read as `0`, exactly like the GUI and the claim check.

## 2. VPlayTime menu placeholders (no PlaceholderAPI needed)

Resolved internally in menu names and lore, values from the active provider. Unknown placeholders are left untouched.

| Placeholder | Meaning |
|---|---|
| `%playtime%` | Formatted effective playtime, e.g. "1 hour 2 minutes" |
| `%playtime_seconds%` | Effective playtime in seconds |
| `%playtime_minutes%` | Effective playtime in whole minutes |
| `%playtime_hours%` | Effective playtime in whole hours |
| `%required_playtime%` | Formatted requirement of the displayed reward |
| `%reward_status%` | `LOCKED`, `CLAIMABLE` or `CLAIMED` |

## 3. External placeholders (read *by* VPlayTime)

When `playtime.provider: placeholder` is set, VPlayTime resolves **whatever placeholder is configured** — nothing is hardcoded. The documented example is the **Statistic** eCloud expansion (`/papi ecloud download Statistic`), which publishes vanilla server statistics:

| Placeholder | Meaning | Provided by |
|---|---|---|
| `%statistic_seconds_played%` | Vanilla `play_time` in seconds | Statistic expansion |
| `%statistic_minutes_played%` | Same in minutes | Statistic expansion |
| `%statistic_hours_played%` | Same in hours | Statistic expansion |

Notes from live verification:

- There is **no** `%statistics_playtime%` — the real prefix is singular `statistic_`.
- Movement statistics are split by vanilla (`sprint_one_cm` vs `walk_one_cm`) — pick the one matching your unit.
- Avoid parameterized variants like `%statistic_time_played:seconds%` (observed returning the seconds *component*, not the total); prefer the dedicated placeholders above and set `unit:` accordingly.
