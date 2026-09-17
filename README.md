# VPlayTime

Configurable Minecraft playtime rewards plugin with level progression, multiple playtime providers, PlaceholderAPI integration, and Paper/Folia support.

- **Platform:** Paper & Folia (1.21.11, `folia-supported`)
- **Java:** 21
- **Current version:** 1.8.2
- **License:** MIT

## What it does

VPlayTime tracks how long players have played and lets them claim tiered rewards as they reach playtime milestones. The shipped default contains **60 levels across 4 menus** (1 hour for level 1 up to 560 hours for level 60), each with locked / claimable / claimed display states, atomic claims (never granted twice), and SQLite persistence.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.11 (Paper or Folia) |
| Java | 21 |
| PlaceholderAPI | **Optional** — only needed for the `placeholder` playtime provider and the `%vplaytime_*%` expansion |

## Installation

1. Download `VPlaytime-<version>.jar` from the [Releases](https://github.com/GQS5/VPlayTime/releases) page.
2. Drop it into your server's `plugins/` folder.
3. Restart the server (first start generates `config.yml`, `messages.yml`, `rewards.yml`).
4. Open `/playtime` in game.

No database setup needed — SQLite storage is created automatically at `plugins/VPlaytime/data/data.db`.

## Commands & permissions

| Command | Permission | Description |
|---|---|---|
| `/vplaytime` (aliases: `/playtime`, `/ptr`) | `vplaytime.use` (default: true) | Open the main rewards menu |
| `/vplaytime <menu>` | `vplaytime.use` | Open a named menu (`menu_2`, …) |
| `/vplaytime reload` | `vplaytime.admin` | Transactionally reload all config files |
| `/vplaytime info <player>` | `vplaytime.info` | Show playtime, provider and claims |
| `/vplaytime reset <player> <reward>` | `vplaytime.reset` | Remove one claim |
| `/vplaytime resetall <player>` | `vplaytime.reset` | Remove all claims (`vplaytime.admin` inherits both) |

Reloads are transactional: an invalid file keeps the previous working configuration active and reports the exact file, key path and reason — never a restart.

## Configuration

Three files in `plugins/VPlaytime/`:

- **`config.yml`** — debug flag, storage autosave (≥ 5s, default 30s), AFK counting, and the **playtime provider** selection.
- **`rewards.yml`** — menus, levels, slots, `required-seconds`, per-state display and reward actions.
- **`messages.yml`** — all player/admin messages (MiniMessage; empty disables).

Full schema: [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md). Reward layout: [`docs/REWARDS.md`](docs/REWARDS.md).

## Playtime providers

`PlaytimeManager.effectivePlaytimeSeconds(UUID)` is the single read everything (GUI, claims, placeholders, admin info, API) goes through. Which clock feeds it is configurable:

```yaml
playtime:
  provider: internal   # default: built-in session timer, no dependencies

  placeholder:
    value: '%statistic_seconds_played%'
    unit: seconds       # seconds | minutes | hours | milliseconds
```

- **`internal`** — VPlayTime's own session timer (stored total + open session). Always available.
- **`placeholder`** — any PlaceholderAPI placeholder, normalized to whole seconds. Requires PlaceholderAPI plus the expansion that provides the value. While active, the internal timer stops (claims and storage keep working; `required-seconds` stays the source of truth).

Example integration — vanilla playtime via the **Statistic** eCloud expansion (`/papi ecloud download Statistic`), which provides `%statistic_seconds_played%`:

```yaml
playtime:
  provider: placeholder
  placeholder:
    value: '%statistic_seconds_played%'
    unit: seconds
```

The placeholder is fully configurable — nothing is hardcoded. Details: [`docs/PLAYTIME-PROVIDERS.md`](docs/PLAYTIME-PROVIDERS.md).

## Placeholders

VPlayTime's own expansion (registered when PlaceholderAPI is installed):

- `%vplaytime_seconds%`, `%vplaytime_minutes%`, `%vplaytime_hours%` — active provider's playtime.

Inside menu lore/names (no PlaceholderAPI needed, values from the active provider):

- `%playtime%` (e.g. "1 hour 2 minutes"), `%playtime_seconds%`, `%playtime_minutes%`, `%playtime_hours%`, `%required_playtime%`, `%reward_status%`.

Details: [`docs/PLACEHOLDERS.md`](docs/PLACEHOLDERS.md).

## Reward levels (shipped default)

60 levels (`level_1`…`level_60`) across `main`, `menu_2`, `menu_3`, `menu_4`, from 1 hour to 560 hours. States render red (locked) → yellow glowing (claimable) → lime glowing (claimed). Money actions use `addmoney <player> <amount>` (adapt to your economy plugin), XP uses vanilla `xp give`, items use direct grants. Claims are atomic: memory reserve → durable insert → execute; failures revoke for retry and can never double-grant.

## Compatibility / dependencies

- Paper and Folia 1.21.11 (`api-version 1.20`, `folia-supported: true`).
- PlaceholderAPI: optional soft dependency (loads before VPlayTime when present; classpath-joined only then, so servers without it are unaffected).
- SQLite via Paper's library loader — no shading, nothing leaks into other plugins.
- No Vault, MySQL or Redis integration.

## Building

```bash
./gradlew clean build
```

Requires JDK 21 (Gradle toolchain resolves it). The JAR lands in `build/libs/VPlaytime-<version>.jar`. CI builds every push/PR with the same command (`.github/workflows/build.yml`).

## Testing

```bash
./gradlew clean test
```

JUnit 5, no server needed (in-memory clocks, temp SQLite databases, YAML fixtures). 286 tests covering config parsing, reload transactions, providers, claims atomicity, GUI math and storage. See [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md).

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). Bug reports and feature requests via [Issues](https://github.com/GQS5/VPlayTime/issues) (templates provided). Security issues: see [`SECURITY.md`](SECURITY.md) — do not open public issues for vulnerabilities.

## License

MIT — see [`LICENSE`](LICENSE).

## Releases

Versioned releases with attached, CI-verified JARs: [Releases](https://github.com/GQS5/VPlayTime/releases). Current: **v1.8.2**. History: [`CHANGELOG.md`](CHANGELOG.md).
