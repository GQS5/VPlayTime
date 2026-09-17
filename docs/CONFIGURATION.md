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

`/vplaytime reload` re-reads all three files under one monitor (concurrent reloads can't interleave) and swaps menus, rewards, messages, globals **and** the active provider together. Listeners, tasks and registrations are created once at enable and never duplicated. The `%vplaytime_*%` expansion is registered once and reads the live provider, so no re-registration is needed.

### Invalid config behavior

Any mistake throws `ConfigError(file, path, reason)`:

- console logs a report (`Reload failed: <file>` → `Invalid value at <dotted.path>` → reason → `Previous configuration remains active.`)
- the player sees the configured message plus the precise reason
- **the previous working configuration (including the previous provider) stays active** — the server keeps running on known-good state

At startup, an invalid config disables the plugin with the same file+path detail instead of running broken.

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
