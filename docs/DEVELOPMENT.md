# Development

## Build

```bash
./gradlew clean build     # full build, tests included; JAR in build/libs/
./gradlew clean test      # tests only
```

- Java 21 (Gradle toolchain; `options.release = 21`, UTF-8).
- `compileOnly` Paper API `1.21.11-R0.1-SNAPSHOT` (+ MiniMessage, which Paper provides at runtime) and PlaceholderAPI `2.11.6` (optional at runtime — the JAR stays thin).
- SQLite driver and JUnit 5 are test-scope only; at runtime Paper's library loader supplies SQLite from `paper-plugin.yml` (`folia-supported: true`, `api-version 1.20`).
- Repos: Maven Central, PaperMC, Sonatype snapshots, ExtendedClip releases (see `settings.gradle` — project repos are locked to settings via `PREFER_SETTINGS`).

## Project structure

```text
src/main/java/site/vackstudio/vplaytime/
├── VPlaytimePlugin.java      # bootstrap: config → storage → providers → menus → listeners
├── config/                   # ConfigManager, MenuRegistry, snapshots, records, ConfigError
├── playtime/                 # PlaytimeManager, PlayerData, PlayerCache, TimeSource
│   └── provider/             # PlaytimeProvider, Internal/Placeholder, PlaytimeUnit
├── papi/                     # PapiGuard, PlaceholderLookup, VPlaytimeExpansion, ExpansionHost
├── reward/                   # ClaimManager, RewardManager, executors, CommandFailure
├── gui/                      # RewardMenu, listener, rendering, Placeholders, sounds
├── model/                    # RewardDefinition/Action/Display/State, StoredData, ClaimResult
├── storage/                  # Storage, SQLiteStorage, executor, migrator
├── listener/                 # join/quit wiring
├── command/                  # /vplaytime
├── admin/                    # info/reset service
└── api/                      # VPlaytimeAPI (public, minimal)
src/main/resources/           # config.yml, rewards.yml, messages.yml, paper-plugin.yml
src/test/java/…                # JUnit 5, mirrors main; FakeTimeSource, temp-DB harnesses
```

## Tests

No server needed: `FakeTimeSource` clocks, real temp-file SQLite, `YamlConfiguration` fixtures, inline schedulers. Conventions: pure validation stays in `config`/`gui`/`reward` static methods so tests never boot Bukkit; production-only Bukkit touchpoints (`BukkitClaimTarget`, `PlaceholderLookup`) are never instantiated in tests — their logic is factored into pure helpers (`CommandFailure`, provider `convert()`, expansion `resolve()`), which are tested.

## Provider architecture

`PlaytimeManager.effectivePlaytimeSeconds(UUID)` is the single read seam; `PlaytimeProvider` implementations are immutable, stateless, fail-closed (`OptionalLong`). The plugin builds and swaps the active provider in `activateProvider()` (enable + successful reload only — failed reloads keep the old instance).

### Adding a new provider

1. Implement `PlaytimeProvider` (`id()`, `playtimeSeconds()`, `external()` as needed). Keep it dependency-free and inject lookups (see `PlaceholderPlaytimeProvider`).
2. Accept the id in `PlaytimeProviderConfig.parse` (+ unit/option parsing if needed).
3. Construct it in `VPlaytimePlugin.activateProvider()`; extend the runtime check in `ConfigManager.checkProviderRuntime` if it has a required plugin.
4. Add unit tests (parse/convert/selection/swap) — no reward, GUI or storage changes needed.

## Reload architecture

`VPlaytimeCommand` → `VPlaytimePlugin.vplaytimeReload()` (synchronized) → `ConfigManager.load()` (synchronized): migrate → parse all files into locals → runtime checks → warn unknown commands → swap rewards + publish snapshot → back in the plugin: swap provider → rebuild menus. Any `IllegalStateException`/`ConfigError` aborts before publishing; console gets file+path+reason, the player gets the reason, old state keeps serving.

## Paper/Folia compatibility

- No sync scheduler, no shared mutable iteration: per-player entity schedulers, one global-tick block for console commands, single-thread storage executor, `ConcurrentHashMap` caches (region threads differ per player).
- GUI/click code runs on the region thread; command dispatch hops to the global thread once per step.
- PAPI classes are referenced only from `papi/` classes loaded behind `PapiGuard.present()` — main-class verification must succeed with or without PlaceholderAPI. Optional dependency declared `load: BEFORE, required: false, join-classpath: true`.

## Coding conventions

- Records for immutable config/model data; `ConfigError(file, path, reason)` for every config failure (never bare `IllegalStateException` from validation).
- Fail closed, log loud: unavailable external data reads as `0`/LOCKED, never invented; warn-once for repeating config-level problems, SEVERE for repeat execution alarms.
- No `System.out`, no new threads per player, no blocking on region threads; storage only via `StorageExecutor`.
- Keep the public `VPlaytimeAPI` minimal; never expose `PlayerData`, storage or GUI internals.
