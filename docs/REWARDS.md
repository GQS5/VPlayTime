# Rewards (shipped default)

Fresh installs ship a working 15-level setup: `level_1`…`level_15` across three 3-row pages (`main`, `menu_2`, `menu_3`, 5 levels each, `order` 1–3), from **1 hour** (level 1) to **112 hours** (level 15). Rewards sit centered (slots 11–15) with previous/next arrows and a small stats button — no filler, no promo blocks.

## States

| State | Look | Meaning |
|---|---|---|
| LOCKED | red candle, no glow | Requirement not met |
| CLAIMABLE | orange candle, no glow, "Click to claim!" | Met, ready to claim |
| CLAIMED | lime candle + glow, "✓ Claimed" | Granted, permanent |

Claimed always wins over playtime.

## Reward contents (level 1 → 15)

- **Money:** `addmoney <player> <amount>`, $1,000 → $55,000. Adapt the command to your economy plugin.
- **XP:** vanilla `xp add <player> <amount>`, 500 → 6,500.
- **Items:** direct grants (e.g. `RAW_IRON 16`) with full-inventory protection.
- **Shards (7 levels):** `addshards <player> <amount>` (VCore shards).
- **Crates (3 levels):** `cc give physical Common 1 <player>` — needs a crates plugin.

`points`/`cc` lines assume those integrations exist; at load VPlayTime warns about command roots no enabled plugin provides, and `/vplaytime info` plus the log show exact failures. See `docs/CONFIGURATION.md`.

## Claim pipeline

1. Click → state re-resolved from current data (never the item's looks).
2. Memory reservation (`tryClaim`) → capacity pre-check → durable DB insert (uniqueness arbiter) → execute in configured order on the right threads (items on the player thread, commands via one global-tick block).
3. Success finalizes; any failure revokes memory + DB row for retry — duplicates are impossible by construction.

Consequences worth knowing:

- A **permanently failing later step re-grants earlier steps on every retry** (observed live with a broken XP command). Keep reward commands working and idempotent; load-time warnings and the repeat-failure SEVERE alarm exist exactly for this.
- Item delivery is all-or-nothing per batch; a full inventory rejects *before* anything durable, so the player just clears space and retries.
- Crash between DB insert and execution (milliseconds) leaves a logged, auditable claim without items — re-grant manually; the reverse order would risk duplicates, which is worse.

## Admin

- `/vplaytime info <player>` — effective vs stored playtime, active provider, claims.
- `/vplaytime reset <player> <reward>` / `resetall` — revoke rows (durable-first, memory converges after; never grants).
- Nothing here touches playtime totals — resets only remove claims.
