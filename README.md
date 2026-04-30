# JExMultiverse

World management plugin for Paper servers with custom generators (void & plot), per-world spawn handling, an in-game editor GUI, internationalization, and a developer API.

- World CRUD with three generation types: `DEFAULT` (vanilla), `VOID` (empty), `PLOT` (grid-based plots with roads and borders)
- Per-world spawn locations plus a single "global spawn" world that overrides default join/respawn behavior
- Inventory-Framework editor GUI for live world configuration (spawn point, global-spawn flag, PvP toggle)
- MiniMessage translations with per-player locales powered by JExTranslate / R18n
- Async-first design — all I/O returns `CompletableFuture`; main-thread work is dispatched via Bukkit scheduler
- Free and Premium editions: Free is capped at 3 worlds with `DEFAULT`/`VOID` only; Premium is unlimited and includes `PLOT`
- Developer API via `MultiverseProvider`, registered with Bukkit's `ServicesManager`
- Backed by JEHibernate 3.0.3 — H2 by default, with MySQL/PostgreSQL/Oracle/SQL Server support


## Table of Contents

- [Requirements](#requirements)
- [Installation](#installation)
- [Configuration](#configuration)
- [Commands](#commands)
- [Permissions](#permissions)
- [Editions](#editions)
- [Developer API](#developer-api)
- [Building from Source](#building-from-source)
- [Credits](#credits)


## Requirements

- Java 21+
- Paper 1.19+ (developed against Paper API 1.21.x)
- JExDependency runtime loader (handles library injection)


## Installation

1. Place the JExMultiverse `.jar` (Free or Premium) into the server's `plugins/` folder.
2. Start the server. Configuration, database, and translation files are generated automatically.
3. Create your first world with `/mv create <name> [environment] [type]`.


## Configuration

### Translations

Translation files live in `plugins/JExMultiverse/translations/`. Shipped languages: `en_US`, `de_DE`. Add a new language by creating `<locale>.yml` and registering it in `translation.yml` under `supportedLanguages`. Reload in-game with `/r18n reload`.

### Database

Hibernate properties are in `plugins/JExMultiverse/database/hibernate.properties`. Default backend is H2 stored at `plugins/JExMultiverse/database/jexmultiverse`. Switch to MySQL, PostgreSQL, Oracle, or SQL Server by editing the connection settings.


## Commands

### Player / Admin Commands

| Command | Aliases | Description |
|---------|---------|-------------|
| `/multiverse` | `/mv` | Root command — opens help |
| `/multiverse create <name> [env] [type]` | `/mv create` | Create a new world |
| `/multiverse delete <world>` | `/mv delete` | Delete a world (unloads + removes files) |
| `/multiverse edit <world>` | `/mv edit` | Open the world editor GUI |
| `/multiverse teleport <world>` | `/mv teleport` | Teleport to a world's spawn |
| `/multiverse load <world>` | `/mv load` | Load a world from the database |
| `/multiverse list` | `/mv list` | List all managed worlds |
| `/multiverse help` | `/mv help` | Show command help |
| `/spawn` | `/pspawn` | Teleport to the resolved spawn location |

**`/mv create`** arguments:
- `name` — world folder name (required)
- `environment` — `NORMAL`, `NETHER`, or `THE_END` (default: `NORMAL`)
- `type` — `DEFAULT`, `VOID`, or `PLOT` (default: `DEFAULT`)

**`/mv edit`** opens a 6-row inventory with:
- Set spawn to the player's current location
- Toggle global-spawn designation (only one world can be global at a time)
- Toggle PvP for this world
- Save changes to the database


## Permissions

| Permission | Description |
|------------|-------------|
| `jexmultiverse.command` | Use `/multiverse` (root) |
| `jexmultiverse.command.create` | Create worlds |
| `jexmultiverse.command.delete` | Delete worlds |
| `jexmultiverse.command.edit` | Open the editor GUI |
| `jexmultiverse.command.teleport` | Teleport to managed worlds |
| `jexmultiverse.command.load` | Load worlds from the database |
| `jexmultiverse.command.list` | List managed worlds |
| `jexmultiverse.spawn` | Use `/spawn` |


## Editions

| Capability | Free | Premium |
|------------|:----:|:-------:|
| World limit | 3 | Unlimited |
| `DEFAULT` generator | ✅ | ✅ |
| `VOID` generator | ✅ | ✅ |
| `PLOT` generator | ❌ | ✅ |
| Editor GUI | ✅ | ✅ |
| Global-spawn / per-world spawn | ✅ | ✅ |
| Developer API | ✅ | ✅ |


## Developer API

JExMultiverse registers `MultiverseProvider` with Bukkit's `ServicesManager`.

```java
// Obtain the provider
MultiverseProvider provider = JExMultiverseAPI.get();

// Async lookups — all return CompletableFuture
provider.getWorld("my_world").thenAccept(opt -> {
    opt.ifPresent(snapshot -> {
        // snapshot is an immutable MVWorldSnapshot record
    });
});

provider.getGlobalSpawnWorld().thenAccept(opt -> { /* ... */ });

provider.getAllWorlds().thenAccept(list -> { /* ... */ });

// Has the world configured a multiverse spawn?
provider.hasMultiverseSpawn("my_world").thenAccept(present -> { /* ... */ });

// Teleport a player using the spawn-resolution chain
// (global spawn → current world's multiverse spawn → default world spawn)
provider.spawn(player).thenAccept(success -> { /* ... */ });
```

`MVWorldSnapshot` is a Java 21 record exposing the world's id, identifier, type, environment name, spawn coordinates, global-spawn flag, PvP flag, and optional enter-permission.

Heavy work happens off the main thread; schedule any Bukkit API calls back onto the primary thread.


## Building from Source

```bash
# Publish local dependencies first
./gradlew :JExCommand:publishToMavenLocal
./gradlew :JExTranslate:publishToMavenLocal
./gradlew :JExDependency:publishToMavenLocal

# Build both editions
./gradlew :JExMultiverse:buildAll

# Or individually
./gradlew :JExMultiverse:jexmultiverse-free:shadowJar
./gradlew :JExMultiverse:jexmultiverse-premium:shadowJar

# Publish to local Maven
./gradlew :JExMultiverse:publishLocal
```

Artifacts:
- `jexmultiverse-free/build/libs/JExMultiverse-<version>-Free.jar`
- `jexmultiverse-premium/build/libs/JExMultiverse-<version>-Premium.jar`


## Credits

- Author: JExcellence — https://jexcellence.de
- Runtime dependency injection: JExDependency
- ORM layer: JEHibernate (Hibernate 7.x)
- GUI framework: InventoryFramework
- Command framework: JExCommand 2.0
- i18n: JExTranslate / R18n
