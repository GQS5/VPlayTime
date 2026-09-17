# Configuration

VPlayTime reads three files from `plugins/VPlaytime/`. Every load (startup or `/vplaytime reload`) validates everything into memory first and only then publishes — a failed load never leaves half-updated configuration behind.

## Files

| File | Contains |
|---|---|
| `config.yml` | `config-version`, debug, storage, playtime provider |
| `rewards.yml` | menus, levels, slots, requirements, actions |
| `messages.yml` | all player/admin texts (MiniMessage; empty disables) |
| `data/data.db` | SQLite storage (WAL) — never edit by hand |

`config-version` must be `3`. Version 1/2 files migrate automatically once (backups `*.bak-v1`, `*.bak-v2`); a leftover `menus.yml` is ignored with a warning, never read.

## `config.yml`

```yaml
config-version: 3

debug:
  enabled: false

storage:
  type: sqlite            # only supported value
  autosave-seconds: 30    # minimum 5

playtime:
  count-afk: true
  provider: internal       # internal | placeholder (see below)

  placeholder:
    value: '%statistic_seconds_played%'
    unit: seconds          # seconds | minutes | hours | milliseconds
```

### `playtime.provider`

- **`internal`** (default) — VPlayTime's own session timer. No dependencies. The `placeholder` block is ignored (but may be prepared in advance — it is not validated while `internal` is selected).
- **`placeholder`** — read playtime from any PlaceholderAPI placeholder, converted from `unit` to whole seconds. Requires PlaceholderAPI **and** the expansion providing the value. `value` must be non-blank (bare names are wrapped to `%…%` automatically); `unit` accepts singular/plural (`second(s)`, `minute(s)…`, `ms`, …). While active, the internal session timer stops — claims, storage and `required-seconds` are unaffected.

### Reload behavior

`/vplaytime reload` re-reads all three files under one monitor (concurrent reloads can't interleave), then runs the full reward preflight (see below): only a completely clean candidate commits — menus, rewards, messages, globals **and** the active provider swap together, and a successful reload also clears suspended rewards. Listeners, tasks and registrations are created once at enable and never duplicated. The `%vplaytime_*%` expansion is registered once and reads the live provider, so no re-registration is needed.

### Reward preflight (fail-closed validation)

Every startup and reload runs all of `rewards.yml` through preflight **before anything activates**:

1. **Structural collect-all parse** — every menu, reward and button is checked (ids, slots, materials, amounts, actions, navigation targets); every problem is recorded, never just the first.
2. **Content merge** — cross-menu copies of a reward id must agree (except slot).
3. **Semantic preflight over every reward** (locked, later pages, claimed — all of them): every action's command syntax (strict `xp add %player% <amount>` — `xp give`/`exp give` are rejected), item materials/amounts, placeholder well-formedness (`%player%`/`%uuid%`/`%claim_id%` only in commands), player tokens on player-targeted commands, and external provider availability (`addmoney`, `addshards`/`points`, `cc`, … must be provided by an enabled plugin — command registration is the evidence).
4. Each reward resolves to **VALID / INVALID / UNVERIFIABLE** (unverifiable = sound but not statically provable, e.g. an `@p` target; never a bypass for obvious errors).
5. **Zero INVALID → atomic commit** of the immutable validated plan. **Any INVALID → full rejection**: the new configuration is discarded completely, the last known-good plan stays active, nothing partially applies.

The console shows the full diagnostic block (`VPlayTime Reward Preflight Validation FAILED` → counts → numbered entries with file, path, reward, type, value, reason, fix → retention line); the player sees the configured `reload-failed` + `reload-detail` summary.

### Invalid config behavior

Any mistake is collected as above (single mistakes still throw `ConfigError(file, path, reason)` with the same 4-line report):

- console logs the full report ending in `Previous configuration remains active.`
- the player sees the configured message plus the precise reason
- **the previous working configuration (including the previous provider) stays active** — the server keeps running on known-good state

At startup, an invalid config no longer disables the plugin: it boots operational with the **reward system DISABLED** — commands, `/vplaytime info` and GUI visibility keep working, but every reward slot renders the dedicated `⚠ CONFIGURATION ERROR` state (configurable via `gui.reward-error-name/lore`, never claimable, never executing) and every claim attempt is refused before touching data, storage or executors. Fix the file and `/vplaytime reload` to reactivate.

Recovery procedure: read the console block (or `/vplaytime info` counts), fix each listed path, reload. A provider that enables after VPlayTime is picked up by the next reload.

### Missing-dependency behavior

- `provider: placeholder` without PlaceholderAPI installed/enabled → startup/reload fails with a clear error naming PlaceholderAPI and the configured placeholder. Set `provider: internal` or install PlaceholderAPI.
- Unknown/unresolving placeholder at runtime → warn-once in console, value reads as unavailable.
- Unavailable or non-numeric provider values **fail closed**: rewards stay LOCKED, GUI shows 0 — playtime is never invented, the plugin never crashes.

## `rewards.yml` (structure)

```yaml
menus:
  main:                       # 'main' must always exist
    name: Playtime Rewards    # human label
    order: 1                  # page sequence, unique, from 1
    title: Playtime            # window title
    rows: 3                   # 1-6
    sounds:
      open: BLOCK_CHEST_OPEN
      claim: ENTITY_PLAYER_LEVELUP
      locked: BLOCK_NOTE_BLOCK_BASS
      already-claimed: ''
    rewards:
      level_1:
        slot: 10
        time: 1h              # 30s/10m/1h/2d/1w — wins over required-seconds
        # required-seconds: 3600
        display:
          name: 'Level 1'
          locked:    {material: RED_CANDLE, lore: [...]}
          claimable: {material: ORANGE_CANDLE, lore: [...]}
          claimed:   {material: LIME_CANDLE, lore: [...], glow: true}
        actions:
          - type: command
            command: 'addmoney %player% 1000'
          - type: command
            command: 'xp add %player% 500'
          - type: item
            material: DIAMOND
            amount: 10
    items:                    # menu buttons (stats, navigation, decor)
      stats:
        slot: 25
        material: CLOCK
        name: 'Your playtime: %playtime_hours%h'
        lore: [...]
        action:
          message: '…'        # or open-menu: <id>, next-page, previous-page, close
    fill:                     # optional filler for empty slots
      material: GRAY_STAINED_GLASS_PANE
      name: ''
defaults:                     # optional file-level display fallback
  reward:
    display: {...}
```

Notes:

- `time` accepts `30s`, `10m`, `1h`, `2d`, `1w` (highest unit wins when both are set).
- Action shortcuts: `- command: "…"`, `- item: "DIAMOND 10"`.
- Command substitution: `%player%`, `%uuid%`, `%claim_id%`. Commands run as **console** on the global tick thread (Folia-safe). At load, VPlayTime warns about command roots no enabled plugin provides (warn-only).
- Max 64 actions per reward. Slots are 0-based; slot clashes and duplicate `order` values fail validation.
- Menu navigation actions: `next-page`, `previous-page`, `open-menu: <id>`, `close`, `message: <text>`. An empty `open-menu:` fails with a precise error.
- `head:` supports `%player%`, names, or `{texture: …}`.
- Sounds accept any registered key (`BLOCK_CHEST_OPEN`, `block.chest.open`, …) plus `{sound:, volume:, pitch:}`; unknown names warn once and stay silent.

## `messages.yml`

`prefix` (prepended everywhere), `loading`, `claim.success/locked/already-claimed/failed`, `admin.reload-success/reload-failed`. All MiniMessage; legacy `&` codes are converted at load. Empty string = sounds-only feedback.
