# Playtime providers

## The abstraction

```text
PlaytimeProvider
├── InternalPlaytimeProvider
└── PlaceholderPlaytimeProvider
```

`PlaytimeProvider` (`playtime/provider/PlaytimeProvider.java`) is the only contract the rest of the plugin knows:

- `playtimeSeconds(UUID)` → normalized whole seconds, or empty when unavailable. Never throws, never blocks, never invents playtime.
- `id()` → `internal`, `placeholder`, later `hexacore` / `vcore`.
- `external()` → whether this source replaces the internal session timer.

Every consumer — reward states, claim validation, menu rendering, GUI placeholders, `/vplaytime info`, the public API, the `%vplaytime_*%` expansion — reads through one seam: `PlaytimeManager.effectivePlaytimeSeconds(UUID)`. It delegates to the active provider and fails closed to `0` (LOCKED, never granted) when the value is unavailable. Reward logic never touches provider types.

## Current implementations

**`InternalPlaytimeProvider`** — the built-in session timer (stored total + open session). Always available, no dependencies. Wired as a method reference into the manager, so there is no construction cycle.

**`PlaceholderPlaytimeProvider`** — resolves any configured PlaceholderAPI placeholder for the player and converts it from the configured unit (`seconds`, `minutes`, `hours`, `milliseconds`) to whole seconds. Integers convert exactly; decimals scale-then-floor (`1.5` minutes → 90 seconds); negatives, NaN/Infinity and non-numeric text are rejected safely with warn-once diagnostics (unknown/unresolved placeholders, invalid values, lookup failures each warn once). Resolution itself is injected as a function, so the class never touches Bukkit or PlaceholderAPI directly. No caching — every call site is event-driven (open, refresh, click, claim) and values must be current.

## External mode

When a provider with `external() == true` is active, the internal session timer stops: joins/quits skip session start/end, so no second clock runs and the frozen stored total is never displayed or compared. Claims still load, save and reset normally; `required-seconds` remains the single source of truth for requirements. Provider swaps are atomic (volatile reference, inside the synchronized reload).

## Future providers

HexaCore and VCore providers are **not implemented**. When they are, they implement `PlaytimeProvider`, get a config id, and are constructed in `VPlaytimePlugin.activateProvider()` — no reward, GUI, command or storage class changes. See `docs/DEVELOPMENT.md` ("Adding a new provider").
