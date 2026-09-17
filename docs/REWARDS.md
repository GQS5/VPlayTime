# Rewards (shipped default)

Fresh installs ship a working 60-level setup: `level_1`…`level_60` across four 6-row pages (`main`, `menu_2`, `menu_3`, `menu_4`, 15 levels each, `order` 1–4), from **1 hour** (level 1) to **560 hours** (level 60). The layout is DeluxeMenus-inspired (reward rows at slots 10–14, 19–23 and 28–32; store info at 16, stats at 25, previous/next at 48/50, close at 49, glass-pane background) but implemented natively — no DeluxeMenus needed. Existing servers keep their own files; only fresh installs receive this default.

## States

| State | Look | Meaning |
|---|---|---|
| LOCKED | red candle, no glow | Requirement not met |
| CLAIMABLE | orange candle, no glow, "Click to claim!" | Met, ready to claim |
| CLAIMED | lime candle + glow, "✓ Claimed" | Granted, permanent |
| ERROR (no valid plan) | redstone block, "⚠ CONFIGURATION ERROR" | Reward system disabled — never claimable, never executes |

Claimed always wins over playtime.

## Reward contents (level 1 → 60)

- **Money:** `addmoney <player> <amount>`, $1,000 → $1,000,000. Adapt the command to your economy plugin.
- **XP:** vanilla `xp add <player> <amount>`, 500 → 125,000.
- **Items:** direct grants (e.g. `RAW_IRON 16`) with full-inventory protection.
- **Shards (30 levels):** `addshards <player> <amount>` (VCore shards).
- **Crates (12 levels):** `cc give physical Common 1 <player>` — needs a crates plugin.

`points`/`cc` lines assume those integrations exist; at load VPlayTime preflight **rejects** the configuration (reward system DISABLED, nothing activates) when a mandatory provider is missing — install the plugin or fix the command, then reload. See `docs/CONFIGURATION.md` (preflight) for the full rule set: strict `xp add %player% <amount>` syntax, `%player%`/`%uuid%`/`%claim_id%`-only placeholders in commands, player tokens on player-targeted commands, and per-reward VALID/INVALID/UNVERIFIABLE verdicts with file+path+fix diagnostics.

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
