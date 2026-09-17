# Changelog

## [Unreleased]

### VPlayTime 1.9 — UX, messages and GUI reliability (2026-09-18)

Claimed-state reliability:

- Full click → validate → persist → execute → refresh → render audit:
  no stale-snapshot path found in 1.8.2 (live-verified on item and
  command rewards), hardened anyway: post-claim refresh now re-resolves
  the menu from the live map (a reload mid-claim can no longer repaint
  a stale layout), and every SUCCESS is verified back against the claim
  set with a SEVERE tripwire if memory and durable state ever disagree.
- GUI renders now resolve the playtime provider ONCE per render
  (previously 2N+1 resolutions per open — 30+ PlaceholderAPI parses
  with an external provider) and freeze one value per frame so all
  slots agree. Open path confirmed hop-free (create → render → open on
  the region thread); the single entity-scheduler hop in claim refresh
  is a Folia requirement, not overhead.
- New visuals: LOCKED red, no glow → CLAIMABLE orange, no glow →
  CLAIMED lime + glow.
- Regression suite: full LOCKED→CLAIMABLE→SUCCESS→CLAIMED flow,
  duplicate-grant prevention, failed-execution stays unclaimed,
  reload-swap and reconnect persistence.

Configuration:

- Removed dead `playtime.count-afk` (parsed, never read); old files
  keep working, the key is ignored. Menu `name` is now optional
  (defaults to title). Everything else audited and kept with reasons.
- Shipped default rebuilt: 3 pages × 5 levels (1–15, slots 11–15),
  no gradients, no filler, no promo blocks, plain-English header
  comments. Level 1–15 data (times, amounts, actions) byte-identical
  to the previous default. Existing servers keep their on-disk files.

Messages (8 → 26 situations, all wired, none hardcoded anymore):

- New: usage, no-permission, players-only, unknown-menu/player,
  no-data, reload-detail, full `/vplaytime info` rows, reset/done
  variants, opt-in menu-opened/menu-closed (silent by default).
- Claim locked now names the requirement (%required_playtime%).
- One `MessageFormat` pass: real placeholders only, missing values
  removed (never raw, never an exception), values MiniMessage-escaped.
- Deliberately NOT messaged (documented in docs/MESSAGES.md): retry
  prompts, unknown-reward clicks, page-turn notices, provider chat.

- Tests: 286 → 301. Docs: new MESSAGES.md; README/CONFIGURATION/
  REWARDS updated to the 3-page default.

Migration (no forced steps, no restarts):

- Existing `rewards.yml` files load unchanged (menu `name` now
  optional, unknown keys ignored). The new 3-page default ships only
  to fresh installs; existing servers keep their levels.
- Existing `messages.yml` files load unchanged, but the 18 new
  situations default to silent. To receive the full new set: back up
  `messages.yml`, delete it, `/vplaytime reload` (regenerates shipped).
- `playtime.count-afk` is ignored when present; safe to delete.
- Production: change remaining `xp give` to `xp add` (see above).

### Production money-farm fix + execution guards (2026-09-18)

- Root cause of the RM7PC incident: `exp give` is not a console command
  (vanilla is `xp`), so every money reward granted action 0 (addmoney)
  then failed action 1 — and revoke-for-retry re-granted the money on
  every click (~$33K farmed, nothing lost or duplicated). Shipped
  default now uses `xp add %player% N` (15 levels, amounts unchanged;
  `give` is not valid `xp` syntax on 1.21 — verified live).
- Load time now warns once per reload about console command roots no
  enabled plugin provides (warn-only, never a load failure) — this would
  have flagged `exp` (and would flag `points`/`cc` if those plugins are
  missing) before any player clicked.
- Repeat-failure alarm: 3 consecutive execution failures on one reward
  raise one SEVERE naming the reward + last error (streak resets on
  success, memory-only). The claim flow itself is unchanged.
- Note: revoke-for-retry inherently re-runs earlier actions, so any
  permanently failing later step can re-grant. The load warning closes
  the realistic hole (misconfigured commands); keep reward commands
  idempotent where possible.
- Tests: +3 (unknown-root collection, streak alarm + isolation + reset).
  286/286 green.

### Precise command-dispatch diagnostics (2026-09-18)

- Failed console command actions now distinguish "unknown command"
  from "executor rejected it (check console-sender support and argument
  syntax)" via a command-map probe, instead of one generic
  "dispatch failed" line. Failure-path only; no hot-path cost, no
  behavior change.
- Tests: +3 pure command-failure wording tests. 283/283 green.

### Claimable material: YELLOW_CANDLE (2026-09-17)

- Shipped 60-level default: claimable state now renders YELLOW_CANDLE
  instead of ORANGE_CANDLE (locked stays RED, claimed stays LIME).
  Display-only; requirements, rewards, slots untouched.

### Configurable Playtime Provider system (2026-09-17)

- New `PlaytimeProvider` abstraction: `playtimeSeconds(UUID)` returning
  normalized whole seconds or empty (unavailable). Consumers (reward
  states, claims, GUI text, admin info, API, expansion) read only through
  `PlaytimeManager.effectivePlaytimeSeconds(UUID)`; reward logic never
  touches provider types, so HexaCore/VCore providers slot in later
  without reward changes.
- Implementations: `InternalPlaytimeProvider` (built-in session timer,
  default) and `PlaceholderPlaytimeProvider` (any configured placeholder
  via PlaceholderAPI, units seconds/minutes/hours/milliseconds, strict
  numeric parsing, warn-once diagnostics, fail-closed to LOCKED/0).
- Config: `playtime.provider: internal|placeholder` +
  `playtime.placeholder.value/unit` in config.yml (default internal;
  migrations write provider defaults). Unknown ids, blank values and bad
  units fail with file + dotted path; placeholder without PlaceholderAPI
  fails startup/reload transactionally (previous provider stays active).
- External mode stops the internal session timer (no second clock);
  claims, storage, slots, amounts, permissions and level requirements
  untouched. `required-seconds` remains the single source of truth.
- PlaceholderAPI is an optional softdepend (loads AFTER when present):
  registers `%vplaytime_seconds/minutes/hours%`; GUI `%playtime_*%`
  values come from the active provider.
- Note: `%statistics_playtime%` does not exist. The known statistics
  integration is PlaceholderAPI's "Statistic" eCloud expansion, which
  provides `%statistic_seconds_played%` (singular `statistic_`, vanilla
  server statistics) — shipped as the documented example.
- Tests: +36 (units/parse/selection, runtime validation, reload swap
  semantics, session gating, GUI math, expansion core, threshold truth).
  280/280 green.

### Reliable reload with full diagnostics (2026-09-16)

- Root cause of silent reload failures: validation errors carried no
  file name, no key path, and the player saw only a generic message
  (e.g. an empty `open-menu:` reported a misleading "needs an 'action'").
- Every config error is now a ConfigError(file, path, reason): console
  logs a 4-line report (file, dotted key path, reason, old-config-kept
  confirmation); the player sees the configured message plus the reason.
- Empty `open-menu:` now fails precisely
  (`menus.<id>.items.<button>.action.open-menu: Expected a menu id but
  received an empty value). Bare null-valued keys are discarded by
  YAML/Bukkit before parsing, so those guide to the action examples.
- Transactional hardening: v1 migration validates before writing;
  whole reload holds one monitor (no interleaved concurrent reloads);
  stale menus.yml warns on every load instead of looking ignored.
- Tests: +7 reload-diagnostic tests (precise error, bare-null case,
  YAML line info, valid→invalid→fixed sequence, 10× cycle stability,
  next-page shortcut). 244/244 green.

### Default scheme: traffic-light candles (2026-09-16)

- Shipped 60-level default now uses RED (locked) / ORANGE_CANDLE
  (claimable) / LIME_CANDLE + glow (claimed), "CLICK TO CLAIM" lore and
  "✓ Claimed" check line, enum-form sounds. Same rewards, hours, slots.

### Full sound registry support (2026-09-16)

- Any registered 1.21.11 sound works: `block.chest.open`,
  `minecraft:block.chest.open`, legacy `BLOCK_CHEST_OPEN` and custom
  namespaces all resolve to one canonical key via a single live-registry
  walk per load (no hardcoded list, no enum access).
- Advanced per-event form with `volume:` (>= 0) and `pitch:` (0-2):
  sounds resolve once into immutable key+volume+pitch records reused on
  every click; playback honors them with no extra scheduler hops.
- Unknown sounds warn once naming menu, field and key, then stay silent;
  invalid volume/pitch fail reload with human errors.
- Shipped default uses registry-key form (already-claimed now pling).
  1.4.x/1.5.x configs load unchanged, no migration.

### Config UX enhancement: legacy colors, time, shortcuts, heads, defaults

- One text path: legacy `&` codes compile to MiniMessage once at load
  (names, lore, titles, messages, button text); typos fail reload naming
  the field. Runtime rendering untouched (no reparse);
- `time: 30s/10m/1h/2d/1w` preferred over `required-seconds` (which still
  works; `time:` wins if both are set);
- Compact actions `- command:` / `- item: "DIAMOND 10"` compile to the
  identical ordered model; button scalar shortcuts `next-page`,
  `previous-page`, `close`, `open-menu: <id>`, `message: ...` (next/prev
  resolve to order neighbors at load);
- Player heads on buttons (`%player%`, names, Base64 textures with
  load-time validation and plain-head fallback); `head:` on rewards is
  rejected with guidance; `%playtime%` placeholders already resolve live;
- Optional `defaults:` (file-level and per-menu) for state
  materials/glow; reward values always win; bad default materials fail;
- Shipped 60-level default rewritten in the new syntax (shorter,
  dogfoods every feature); 1.4.0 files load unchanged, no migration.

### Menu buttons: message, display-only, fill (2026-09-16)

- Button click actions are now a list: `message:` (send text, menu stays
  open), `open-menu:` / `close:` (finish the click). No `action:` section
  means a pure display button; `%playtime%` placeholders resolve live in
  button name/lore (stats readouts need no other plugin).
- New per-menu `fill:` background for empty slots (material/name/lore).
- Converted the old DeluxeMenus decor (close/store/stats/next/prev +
  glass filler, custom heads → vanilla BARRIER/EMERALD/CLOCK/ARROW) into
  the shipped 60-level default. Live-verified on Folia: all buttons
  render, next/prev switch menus, close shuts, store message arrives.

### Readable gradients in default config (2026-09-16)

- Converter now folds per-character `&#hex` runs into MiniMessage
  `<bold><gradient:#first:#last>Word</gradient></bold>` (renders the
  same, but a human can finally edit it).
- Default `rewards.yml`: 219 KB → 134 KB. Live-verified on Folia
  (reload OK, all menus open, zero MiniMessage errors).

### Easier display config: shared lore (2026-09-16)

- `display.lore` (and `name`) written once is now shared by the locked,
  claimable and claimed looks; a single look can still set its own
  `lore:` to override (even empty). Old files with per-state lore keep
  working unchanged — explicit always wins, no migration needed.
- Default `rewards.yml` regenerated in the compact shape (3700 → 3000
  lines); new DISPLAY guide section in the header.
- Tests: +3 fallback/override/empty unit tests. 196/196 green.

### Shipped default: 60 playtime levels (2026-09-16)

- `rewards.yml` default is now the converted 60-level setup (4 menus × 15
  rewards, 1h → 560h, original candle materials/names/lore, `addmoney`
  economy commands). Fresh installs boot straight into the full ladder.
- Existing servers keep their own `rewards.yml` (never overwritten).
- New `ShippedDefaultsTest`: the packaged defaults must always parse
  (4 menus, 60 rewards, orders 1–4, config-version 3).

### Menu buttons — navigation arrows & close buttons (2026-09-16)

- Menus accept an optional `items:` section: non-reward buttons with
  slot/material/name/lore/glow plus one click action (`open-menu: <id>`
  or `close: true`). Buttons never touch player data, claims or storage.
- Validated like rewards: id pattern (hyphens rejected with guidance),
  slot range, slot collisions against rewards and other buttons, unknown
  `open-menu` targets named with the available menu list.
- Clicking a button opens the target menu (with its open sound) or closes
  the inventory; reward clicks unchanged. No config migration (additive,
  optional section, still `config-version: 3`).
- Tests: 184 previous green; +7 MenuRegistry item tests (parse, hyphen id,
  slot clashes, bad slot/material/action, unknown target). Live: arrow
  main → menu_2 clicked by bot, target menu opened.

### Phase 11 — High-Performance Reward Action Engine (2026-09-16)

- Execution plans: consecutive same-context actions group into runs
  (items batch, commands block); configured order is now authoritative —
  `item, command, item` executes across player → global → player hops
  instead of the old items-first reorder (contract change, tests updated).
- Item batches cost ONE inventory scan, ONE combined ItemGrant plan, ONE
  delivery however many actions; commands cost ONE global block with
  in-order dispatches; every claim costs ONE durable claim op.
- ClaimManager drives plan steps directly against ClaimTarget (delivery
  stays out of business logic); executor field removed from its wiring.
  Single-action executor primitives stay tested and available.
- Validation: empty actions fail, >64 actions fail, failures name
  `action i/n` with type and reason; no stack traces reach players.
- rewards.yml gained a REWARD ACTIONS guide; default reward_2 is now a
  3-item example (diamonds/gold/emeralds). README gained
  "Adding Multiple Items to One Reward".
- Tests: 164 previous green; +20 new (ExecutionPlan 6, ActionEngine 12:
  1/5/10/20 matrix, merging, command blocks, hop counts, partial
  capacity, failure-then-retry, 100-spam concurrency, timing benchmark;
  MenuRegistry empty/limit 2). Measured claim cost: 1 action 424us,
  5 567us, 10 569us, 20 1097us — counts constant (1 delivery, 1 claim
  op, 1 player hop, 0 dispatches) at every size.

### Phase 10 — Configuration UX & Built-in Documentation (2026-09-16)

- Single content file: menus merged into `rewards.yml`
  (`menus → menu → settings + rewards with slots`); `menus.yml` retired
  (left in place, no longer read). `config-version: 3` with automatic
  v1→v3 and v2→v3 migration (backups first, result validated before
  writing, user files never overwritten).
- Menus gained `name` (human label, ID stays for files/commands) and
  `order` (unique, from 1); runtime exposes order-sorted pages plus
  next/previous/page-index foundation (no nav buttons yet).
- Same reward id may repeat across menus with independent slots; content
  must agree (validated) and claim state stays shared by id.
- All validation errors rewritten in human terms naming file, menu and
  reward (e.g. slot 30 in a 3-row menu). storage.type enforced (sqlite).
- rewards.yml rewritten as a teaching file: add-reward/add-menu guides,
  ID-vs-name-vs-title explainer, slot diagram, time table, common
  mistakes, fully-commented reward_1 copy template, real values only.
- README rewritten for the final structure with Adding-a-Reward/Menu,
  Changing-Position/Playtime guides; documents only shipped features.
- Sounds: unknown names still warn-and-silent (deliberate: a typo must
  not block a whole reload).
- Tests: 160 previous green; now 164 green. New ConfigParsingTest (6:
  globals + messages), MenuRegistryTest rewritten (19: nested structure,
  name/order, human errors, order sorting, page/next/previous), 
  RewardManagerTest rewritten (7: nested merge, cross-menu consistency,
  slot independence); flat-schema tests superseded. DatabaseMigrator
  tests untouched (5 green).

### Phase 9 — Menu Engine + Configuration Redesign (2026-09-16)

- Config split into 4 files: `config.yml` (globals, v2), `messages.yml`,
  `rewards.yml`, `menus.yml`. One-time automatic v1→v2 migration with
  `config.yml.bak-v1` backup; existing files never overwritten.
- Rewards no longer own slots. Menus map reward IDs to slots; unlimited
  menus, sizes, placements; same reward reusable across menus with shared
  claim state. `/vplaytime <menu>` opens any menu; `main` is the default.
- Per-menu sounds (open/claim/locked/already-claimed); invalid names warn
  and stay silent, never a load failure.
- Transactional reload across all 4 files: parse-then-publish snapshot
  (RewardManager parse/swap split + immutable ConfigSnapshot); failures
  keep old menus AND old rewards.
- Database moved to `data/data.db` with safe copy-then-validate migration
  (original kept, marker file, both-with-data conflict refuses startup).
- Validation: bad rows/titles, out-of-range or shared slots, unknown
  rewards, missing `main` all fail fast with actionable messages.
- 17 new tests (MenuRegistry 12, DatabaseMigrator 5, parse/swap
  transactionality). 143 previous green.

### Phase 8 — Release Candidate QA (2026-09-16)

- Found live (mineflayer bot, HIGH): console dispatch throws
  `Dispatching command async` from entity/region threads —
  `ensureGlobalTickThread`. Command actions now run on the global tick
  thread via new GlobalScheduler/BukkitGlobalScheduler; items stay on the
  player thread (items-then-commands documented). Verified live: say
  broadcast received, mixed claim durable.
- Claim latency observability: debug log carries reserve→done milliseconds
  (measured 46ms live incl. SQLite insert + 3 scheduler hops).
- RCON/panel operability: async admin outcomes mirrored to server log
  ([admin]) since RCON responses race async completions.
- MenuSounds: zero-deprecation registry access (keyStream + get).
- AdminService.reset/resetAll: post-revoke verification load, memory
  converges TO durable truth (concurrent re-claim survives).
- Zero compiler warnings.
- Live QA (all on Folia 1.21.11, real bot): GUI states by material,
  multi-item + mixed delivery, 15-click spam → 1 grant, full-inventory
  reject→retry, reset→reclaim, disconnect race (no dup), dirty-player
  shutdown (exact 12s banked), 6 boot cycles, version-100 refusal with zero
  loss, 9B-second player, 15-min soak (threads/RSS flat, 0 errors).
- 3 new tests (scheduler routing, items-before-commands, global-throw
  revoke) + 1 race regression (reset-keeps-reclaim). 140 previous green.

### Phase 7 — Hardening, Admin & Release Readiness (2026-09-16)

- Admin: `/vplaytime info/reset/resetall` (+reload) with per-command
  permissions (use/info/reset/admin), tab completion, async storage with
  thread-safe feedback. `give` deliberately omitted (no second reward path).
- `admin.AdminService`: UUID-based, tested; revoke-before-memory ordering
  (documented divergence analysis); playtime never touched by resets.
- `api.VPlaytimeAPI`: minimal reads + same-path claim; no internals exposed.
- `Storage.revokeAllClaims`, `PlayerData.unclaimAll`, DB `user_version`
  (refuses newer, stamps older; never deletes).
- `config-version: 1` (unsupported fails safe; missing warns + assumes 1).
- ConfigManager.load is transactional (locals-then-assign).
- GUI claim feedback messages (configurable, empty = sounds-only).
- Startup summary line; MIT LICENSE; full README.
- Audits: no BukkitScheduler, no TODOs, no System.out, single bounded
  shutdown get(), no storage-in-GUI, no GUI-in-core, jar has no test
  artifacts, versions agree (1.0.0).
- 16 new tests (10 admin incl. offline/reconnect/restart, 2 API, 3 storage
  versioning/revoke-all, 1 unclaim-all). 124 previous green.

### Phase 6 — Production GUI (2026-09-16)

- `gui/`: MenuHolder (identity, no title matching), RewardMenu (open/render/
  in-place refresh), RewardMenuListener (cancel-all, slot resolution, result
  feedback), RewardStateRenderer (pure resolve, tested), ItemFactory
  (MiniMessage + hidden-enchant glow, server-only), PlaytimeFormat,
  Placeholders (%playtime%/%playtime_seconds%/%minutes%/%hours%/
  %required_playtime%/%reward_status%), MenuSounds (registry-based, warn-once).
- `reward/BukkitClaimTarget`: live-player delivery over ItemGrant planner.
- `RewardManager`: findBySlot + slot map. Command opens menu / reload (admin,
  keeps old on failure, no PlayerData/storage touched). Messages configurable.
- Clicks: locked/claimed = sound only (ClaimManager untouched); claimable =
  ClaimManager → sound + refresh from actual ClaimResult. Stale visuals can't
  bypass (current state resolved per click). No DB on render or click.
- 12 new tests (11 GUI core + slot lookup). Bukkit-only paths (ItemFactory
  build, listener events, live target) reviewed, covered by future live test.

### Phase 5 — Reward Execution Hardening (2026-09-16)

- `reward.ItemGrant`: pure slot planner — all item actions planned atomically
  against a snapshot (merge partials, fill empties, custom stack sizes);
  empty plan = nothing mutates. No rollback logic needed. Server registry
  never touched (stack sizes injected); Bukkit adapter feeds/applies in Phase 6.
- Executors refactored to single-action + ordered `executeAll` with
  failed-action index; configured order preserved (commands and items
  interleave as written).
- `ClaimManager`: %player%/%uuid%/%claim_id% substitution in one place;
  second capacity check on the player thread immediately before delivery;
  scheduler-throw revokes durable row + memory; per-grant info logs gated
  behind new `debug.enabled` plumbing (success silent by default).
- `config.ConfigManager`: exposes `debugEnabled()`.
- Tests use a slot-based fake inventory (36 slots, merging, real capacity).
- 26 new tests: 9 planner, 6 executors, 2 parse boundaries, 9 claim hardening
  (merge, full-inventory, multi-item atomic reject+retry, late capacity change,
  offline/reconnect, command failure/retry, mixed order, no-command perf,
  scheduler-throw revoke). All 86 previous tests green.
- Crash-window verdict (documented): durable-first kept; window is the
  scheduler hop (µs–ms); duplicates impossible by construction; loss is
  logged with execution id for audit. No RESERVED/GRANTED protocol — the
  machinery would exceed the risk it mitigates.

### Phase 4 — Reward Core & Atomic Claims (2026-09-16)

- `model`: RewardState, RewardAction (sealed Item/Command), RewardDisplay,
  RewardDefinition (immutable), ClaimResult (status + detail).
- `reward.RewardManager`: config parsing/validation (id, slot, required,
  materials, action types, amounts), immutable map, stateFor (CLAIMED wins),
  atomic swap on reload (failed reload keeps old).
- `playtime.PlayerData`: claimed set, tryClaim/unclaim (atomic, versioned),
  loaded() factory, snapshots carry claims.
- `storage`: recordClaim (playtime upsert + claim insert, one txn, conflict
  boolean) + revokeClaim compensation; savePlayer still players-only.
- `reward.ClaimManager`: durable-first order (reserve → DB ack → execute);
  memory + PK double protection, revoke on execution failure, unreserve on
  storage failure, conflict self-heal; Folia-safe via PlayerScheduler.
- Executors: ItemActionExecutor (native, no /give), CommandActionExecutor
  (%player%), DelegatingRewardExecutor. Full-inventory → reject, retryable.
- `reward.BukkitPlayerScheduler`: Folia entity-scheduler continuations.
- `config.ConfigManager`: reward-detail validation moved to RewardManager
  (single owner); invalid rewards still disable startup with clear message.
- 46 new tests (13 parse/validate, 5 states, 9 PlayerData claims, 4 claim
  storage, 15 claim manager incl. 100-thread × 1-grant, 100-player,
  restart, failure/retry). Measured: ~3.1k full claims/s, ~6.8M memory
  validations/s.
- Found in testing: Material.isItem() needs the server registry — parse gates
  on matchMaterial + AIR instead (documented).

### Phase 3 — Persistent Storage, SQLite (2026-09-16)

- `model.StoredData`: immutable persistence snapshot (uuid, playtime, claims,
  version); async tasks never touch live PlayerData.
- `storage.Storage`: minimal async interface (loadPlayer/savePlayer/close).
- `storage.StorageExecutor`: single-thread worker, ordered saves, bounded shutdown.
- `storage.SQLiteStorage`: `plugins/VPlaytime/data.db`, WAL+NORMAL+busy_timeout+FK
  (reasoning documented), PreparedStatements only, explicit commit/rollback,
  `players` + future `claims` tables, negative values sanitized to 0.
- Driver via Paper library-loader (`libraries:`), test-scoped Gradle dep —
  no shading, no API leakage.
- `PlayerData`: version counter, `snapshot()`, `markCleanIfVersion()`
  (older save can never clean a newer change).
- `PlaytimeManager`: async join-load with quit-race guard, quit-save,
  `saveDirtySnapshots()` (autosave, dirty only), `shutdown()` (bounded flush).
- Plugin: storage init (clean disable on failure, never fake persistence),
  AsyncScheduler autosave, durable onDisable.
- 21 new tests (12 storage, 9 integration): restart simulation, ordering,
  20-player concurrency, quit-during-load hammer, failure-keeps-dirty.

### Phase 2 — PlayerData, Session Tracking, Cache & Tests (2026-09-16)

- `playtime.TimeSource` / `SystemTimeSource`: injectable epoch-seconds clock.
- `playtime.PlayerData`: UUID, stored seconds, nullable session start, dirty flag;
  synchronized session methods, read-only effective playtime, backward-clock
  clamp (never subtracts), duplicate-start keeps original clock.
- `playtime.PlayerCache`: ConcurrentHashMap-backed UUID→PlayerData store
  (Folia region threads justify the concurrent map).
- `playtime.PlaytimeManager`: handleJoin/handleQuit lifecycle, read-only
  effective-playtime query, quit returns finalized data for Phase 3 persistence.
- `listener.PlayerListener`: MONITOR join/quit delegates, registered in bootstrap.
- JUnit 5 (`./gradlew test`): 19 tests, all passing.
- Design note: quit with 0 elapsed marks nothing dirty (nothing changed to save);
  rejoin starts at 0 until Phase 3 persistence restores stored totals.

## [Unreleased]

### Phase 1 — Foundation (2026-09-16)

- Gradle 8.8 project, Java 21 toolchain, `paper-api:1.21.11-R0.1-SNAPSHOT` (compileOnly).
- `paper-plugin.yml`: Folia support, `VPlaytimePlugin` bootstrap.
- `ConfigManager`: loads and validates storage, menu, rewards (slots, materials,
  playtime, action shape, sounds); fails safe with clear message on invalid config.
- `/vplaytime` (`/playtime`, `/ptr`) stub via Paper `BasicCommand` + lifecycle registration.
- Default `config.yml` with 3 rewards.
- Verified: clean build (zero warnings), clean enable on Folia 1.21.11,
  invalid-material config disables plugin safely.
