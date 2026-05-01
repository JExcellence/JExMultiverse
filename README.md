# JExMultiverse

World management + plot ownership plugin for Paper servers. Custom generators (void & plot), per-world generation overrides, schematic import (Bukkit + WorldEdit/FAWE), per-plot ownership with trusted/denied members and configurable flags, plot merging, in-game GUIs throughout, full developer API.

- World CRUD with three generation types: `DEFAULT` (vanilla), `VOID` (empty), `PLOT` (grid-based plots with roads and walls)
- Per-world plot generation overrides on `/mv create` (plot size, road width, schematic to paste at every plot)
- Schematic import — Bukkit `.nbt` (native, no deps) + WorldEdit/FAWE `.schem`/`.schematic` (soft-dep)
- Plot ownership — claim, unclaim, trust/untrust, deny/undeny, home, list
- Plot merging by player facing — joins adjacent same-owner plots, fills the road, removes walls; permission-capped group size
- 8 enforced plot flags — `pvp`, `mob-spawning`, `explosion`, `fire-spread`, `keep-inventory`, `entry`, `liquid-flow`, `ice-form-melt` — per-plot override or default
- Owner-customisable plot border via `/plot border <material>` — visual signal that a plot is claimed (default border colour differs from unclaimed walls)
- Per-world spawn locations + global spawn — `/spawn` resolves global → current-world MV spawn → default world
- Inventory-Framework GUIs everywhere — `/mv edit`, `/mv list`, `/plot menu` (members + flags + home + unclaim)
- MiniMessage translations with per-player locales (en_US, de_DE shipped) via JExTranslate / R18n
- Async-first design — all I/O returns `CompletableFuture`; main-thread work is dispatched via Bukkit scheduler
- Free and Premium editions: Free is capped at 3 worlds with `DEFAULT`/`VOID`; Premium is unlimited and includes `PLOT`
- Public API via `MultiverseProvider`, registered on Bukkit's `ServicesManager` — exposes plot grid coords/bounds for external plugins
- Backed by JEHibernate 3.0.3 — H2 by default, MySQL/PostgreSQL/Oracle/SQL Server supported


## Table of Contents

- [Requirements](#requirements)
- [Installation](#installation)
- [Configuration](#configuration)
- [Commands](#commands)
  - [World commands (`/mv`)](#world-commands-mv)
  - [Spawn command](#spawn-command)
  - [Plot commands (`/plot`)](#plot-commands-plot)
- [Permissions](#permissions)
- [Plot system](#plot-system)
  - [Claiming](#claiming)
  - [Trusted / denied members](#trusted--denied-members)
  - [Flags](#flags)
  - [Custom border](#custom-border)
  - [Merging](#merging)
  - [GUI menu](#gui-menu)
- [Schematics](#schematics)
- [Editions](#editions)
- [Developer API](#developer-api)
- [Building from Source](#building-from-source)
- [Credits](#credits)


## Requirements

- Java 21+
- Paper 1.19+ (developed against Paper API 1.21.x)
- JExDependency runtime loader (handles library injection)
- Optional: WorldEdit or FastAsyncWorldEdit — enables `.schem`/`.schematic` schematic loading. Bukkit `.nbt` works without it.


## Installation

1. Place the JExMultiverse `.jar` (Free or Premium) into the server's `plugins/` folder.
2. Start the server. Configuration, database, command, schematic, and translation directories generate automatically.
3. Create your first world with `/mv create <name> [environment] [type]`.


## Configuration

### `config.yml`

Plot-world generation defaults — applied to every plot world unless overridden at create time:

```yaml
plot-world:
  plot-size: 16              # edge length of each plot in blocks
  road-width: 5              # width of road grid between plots
  plot-height: 64            # y-coordinate of the plot surface
  wall-height: 1             # 0 disables walls entirely
  road-material: STONE_BRICKS
  wall-material-unclaimed: STONE_BRICK_WALL        # default for unclaimed plots
  wall-material-claimed:   MOSSY_STONE_BRICK_WALL  # default after a plot is claimed
  layers:
    - { material: DIRT,        min-y: 1,  max-y: 62 }
    - { material: GRASS_BLOCK, min-y: 63, max-y: 63 }
  schematic-offset-x: 0      # paste offset relative to plot NW corner
  schematic-offset-y: 1
  schematic-offset-z: 0
```

When a plot is claimed, JExMultiverse repaints the plot's perimeter with the
claimed wall material — by default `MOSSY_STONE_BRICK_WALL` so it visually
differs from the surrounding unclaimed `STONE_BRICK_WALL`. Owners can
override with `/plot border <material>` (see below). The wall stripes
between merged plots are cleared automatically.

### Translations

Translation files live in `plugins/JExMultiverse/translations/`. Shipped languages: `en_US`, `de_DE`. New keys from plugin upgrades are merged into existing on-disk YAML at startup, so admin customizations are preserved. Add a new language by creating `<locale>.yml` and registering it in `translation.yml` under `supportedLanguages`. Reload in-game with `/r18n reload`.

### Database

Hibernate properties are in `plugins/JExMultiverse/database/hibernate.properties`. Default backend is H2 stored at `plugins/JExMultiverse/database/jexmultiverse`. Switch to MySQL, PostgreSQL, Oracle, or SQL Server by editing the connection settings. Schema migrations are automatic via `hbm2ddl=update`.


## Commands

### World commands (`/mv`)

| Command | Description |
|---------|-------------|
| `/mv` | Show command help |
| `/mv create <name> [env] [type] [plot_size] [road_width] [schematic]` | Create a new world. Last three args are PLOT-only |
| `/mv delete <world>` | Delete a world (unloads + removes files) |
| `/mv edit <world>` | Open the world editor GUI |
| `/mv teleport <world>` | Teleport to a world's spawn |
| `/mv load <world>` | Load a world from the database |
| `/mv list` | Paginated GUI of all managed worlds (text fallback for console) |
| `/mv applyschematic <world> [schematic]` | Re-paste a schematic into every plot in loaded chunks |
| `/mv help` | Show command help |

**`/mv create` arguments:**
- `name` — world folder name (required)
- `environment` — `NORMAL`, `NETHER`, or `THE_END` (default: `NORMAL`). Tab-completed.
- `type` — `DEFAULT`, `VOID`, or `PLOT` (default: `DEFAULT`). Tab-completed.
- `plot_size` — per-world plot edge length override (PLOT only)
- `road_width` — per-world road width override (PLOT only)
- `schematic` — schematic file name without extension, dropped into `plugins/JExMultiverse/schematics/` (PLOT only)

**`/mv edit <world>`** opens a compact 3-row GUI with:
- Set spawn to your current location
- Toggle global-spawn designation (auto-clears it from any other world)
- Toggle PvP
- Cycle time of day (live world only — not persisted)
- Cycle weather: clear → rain → storm (live world only)
- Cycle difficulty: peaceful → easy → normal → hard (live world only)
- Save persisted fields to the database

### Spawn command

| Command | Aliases | Description |
|---------|---------|-------------|
| `/spawn` | `/pspawn` | Teleport to the resolved spawn location |

Resolution order: global-spawn world → current world's MV-set spawn → Bukkit default-world spawn. Each tier validates the world is loaded.

### Plot commands (`/plot`)

Aliases: `/p`, `/plots`.

| Command | Description |
|---------|-------------|
| `/plot menu` | Open the plot management GUI |
| `/plot claim` | Claim the plot you're standing on |
| `/plot unclaim` | Release the plot you're standing on |
| `/plot info` | Show owner + member counts |
| `/plot trust <player>` | Allow another player to build |
| `/plot untrust <player>` | Remove a trusted player |
| `/plot deny <player>` | Block a player from this plot |
| `/plot undeny <player>` | Lift a deny |
| `/plot home [n]` | Teleport to your nth owned plot |
| `/plot list` | List your owned plots (count vs. cap) |
| `/plot flag <set\|remove\|list> [flag] [value]` | Manage plot flags |
| `/plot border [material]` | Change your plot's wall material (omit material to reset) |
| `/plot merge` | Merge with the plot you're facing |
| `/plot unmerge` | Split this plot off the merge group |
| `/plot help` | Show command help |


## Permissions

### World management

| Permission | Description |
|------------|-------------|
| `jexmultiverse.command` | Use `/multiverse` (root) |
| `jexmultiverse.command.create` | Create worlds |
| `jexmultiverse.command.delete` | Delete worlds |
| `jexmultiverse.command.edit` | Open the world editor GUI |
| `jexmultiverse.command.teleport` | Teleport to managed worlds |
| `jexmultiverse.command.load` | Load worlds from the database |
| `jexmultiverse.command.list` | List managed worlds |
| `jexmultiverse.command.applyschematic` | Retroactively paste a schematic into a world's plots |
| `jexmultiverse.spawn` | Use `/spawn` |

### Plot management

| Permission | Description |
|------------|-------------|
| `jexplots.command` | Use `/plot` (root) |
| `jexplots.command.claim` | Claim plots |
| `jexplots.command.unclaim` | Unclaim plots |
| `jexplots.command.info` | View plot info |
| `jexplots.command.trust` / `.untrust` | Manage trusted members |
| `jexplots.command.deny` / `.undeny` | Manage denied players |
| `jexplots.command.home` | Teleport to owned plots |
| `jexplots.command.list` | List owned plots |
| `jexplots.command.flag` | Manage plot flags |
| `jexplots.command.border` | Change a plot's wall material |
| `jexplots.command.merge` / `.unmerge` | Merge / unmerge plots |
| `jexplots.command.menu` | Open the plot menu GUI |
| `jexplots.claim.<n>` | Allow up to `n` claimed plots (highest matching wins) |
| `jexplots.claim.unlimited` | No claim cap |
| `jexplots.merge.<n>` | Allow merged groups up to `n` plots |
| `jexplots.merge.unlimited` | No merge cap |
| `jexplots.bypass.protect` | Staff bypass — ignore protection on all plots |


## Plot system

JExMultiverse ships a self-contained plot system for `PLOT`-type worlds. No PlotSquared dependency.

### Claiming

Stand on the plot you want to claim and run `/plot claim`. The claim limit is read from `jexplots.claim.<n>` permissions (default 1, `unlimited` available). The protection listener immediately gates block place/break, container interact, bucket use, fire ignition, and PvP between non-trusted players.

### Trusted / denied members

- `/plot trust <player>` — let them build, break, interact, and use containers.
- `/plot deny <player>` — store the role; full entry blocking is on the Phase 2D+ roadmap (currently surfaces in `/plot info` and the GUI but doesn't gate movement yet).

Membership is per-plot. Merging plots A + B does not propagate trusted lists across the group — each plot keeps its own list.

### Flags

Boolean overrides per plot. Default is the listed value when no override is set.

| Flag | Default | What it does |
|------|:-------:|--------------|
| `pvp` | `false` | When `true`, anyone can fight in this plot. When `false` (default), only owner ↔ trusted PvP is allowed. |
| `mob-spawning` | `true` | When `false`, hostile mobs (creepers, zombies, phantoms, slimes, ghasts, etc.) don't naturally spawn. Spawn eggs / spawners still work. |
| `explosion` | `false` | When `false`, blocks inside this plot survive creeper, TNT, wither, and end-crystal explosions. Other plots / terrain in the same blast still take damage. |
| `fire-spread` | `false` | When `false`, fire won't spread or burn blocks. You can still light fires; they just don't propagate. |
| `keep-inventory` | `false` | When `true`, players who die inside the plot keep their items + XP. Drops are cleared and dropped XP is zeroed. |
| `entry` | `true` | When `false`, only the owner and trusted members can enter — others are pushed back when they cross the plot boundary. Denied players are always blocked regardless of this flag. |
| `liquid-flow` | `false` | When `false`, water and lava can't flow into the plot — useful for protected builds adjacent to fluids. |
| `ice-form-melt` | `false` | When `false`, ice and snow stay where they're placed — they neither melt in warm biomes nor form in cold ones. |

CLI: `/plot flag set pvp true`, `/plot flag remove pvp`, `/plot flag list`. GUI: `/plot menu` → flags slot (4×2 grid of toggle slots).

### Custom border

`/plot border <material>` repaints the plot's perimeter walls with the
chosen block. Tab completion suggests typical wall / fence materials
(`stone_brick_wall`, `mossy_stone_brick_wall`, `cobblestone_wall`,
`oak_fence`, `iron_bars`, `glass`, etc.) but accepts any solid block. Run
`/plot border` with no argument to reset to the default claimed material
from `config.yml`. Walls between merged plots stay cleared regardless.

### Merging

Stand on your plot, face the adjacent plot you want to merge with, and run `/plot merge`. JExMultiverse uses `player.getFacing()` to pick the neighbor cell — no direction argument needed.

What happens:
- Both plots are tagged with a shared `mergedGroupId`.
- The road between them is filled with your plot's terrain layers.
- The 3-block walls facing each other are cleared.
- Other walls (around the rest of the merged group) stay.

`/plot unmerge` reverses the visual block updates between this plot and any adjacent group members and clears the group ID. If only one plot remains in the group afterwards, its group ID is cleared too (a group of one is just a standalone plot).

The combined group size is capped by `jexplots.merge.<n>` (highest matching) or `jexplots.merge.unlimited`. Default is 1 (no merging).

### GUI menu

`/plot menu` opens a 3-row hub:

```
.   .   .   . [owner head] .   .   .   .
.  [members] [flags] [home] [unclaim]  .
.   .   .   .   .   .   .   .   .
```

- **Owner head** — player skin, lore shows owner / world / grid coords / trusted+denied counts / merged Y/N.
- **Members** — paginated player heads of trusted (green) and denied (red) members. Click an entry to remove the role.
- **Flags** — one toggle slot per flag. Click flips the value and updates the slot in place; lore reflects effective value + (default/override).
- **Home** — teleport to plot center, closes the menu.
- **Unclaim** — release the plot.

Adding members stays as `/plot trust <player>` and `/plot deny <player>` because the offline-player tab completion is smoother on the command line.


## Schematics

Drop schematic files into `plugins/JExMultiverse/schematics/`:

- `<name>.nbt` — Bukkit's native structure block format. No external dependency.
- `<name>.schem` / `<name>.schematic` — WorldEdit / FastAsyncWorldEdit clipboard formats. Loaded only if WE/FAWE is installed; the boot log says which path is active.

Resolution order is `.nbt` → `.schem` → `.schematic`. The schematic pastes at every plot's NW corner during chunk generation, plus configurable offset (`schematic-offset-x/y/z` in `config.yml` — defaults `0,1,0` for "one block above plot surface").

```
/mv create village normal plot 24 5 my_house    # uses my_house.* during chunkgen
/mv applyschematic village                       # retroactively paste into loaded chunks
/mv applyschematic village other_house           # override the world's stored schematic name
```

**Caveats:**
- WE-format schematics can't paste during chunk generation (the edit-session API requires a real `World`, not a `LimitedRegion`). Use `/mv applyschematic` once chunks are loaded.
- `.nbt` schematics paste during generation AND retroactively, no manual step needed.


## Editions

| Capability | Free | Premium |
|------------|:----:|:-------:|
| World limit | 3 | Unlimited |
| `DEFAULT` generator | ✅ | ✅ |
| `VOID` generator | ✅ | ✅ |
| `PLOT` generator | ❌ | ✅ |
| Plot ownership / flags / merging | ❌ | ✅ |
| Schematic import | ❌ | ✅ |
| World editor GUI | ✅ | ✅ |
| `/spawn` + global spawn | ✅ | ✅ |
| Developer API | ✅ | ✅ |


## Developer API

JExMultiverse registers `MultiverseProvider` with Bukkit's `ServicesManager`.

```java
import de.jexcellence.multiverse.api.JExMultiverseAPI;
import de.jexcellence.multiverse.api.MultiverseProvider;
import de.jexcellence.multiverse.api.PlotCoord;
import de.jexcellence.multiverse.api.PlotBounds;

// Obtain the provider
MultiverseProvider mv = JExMultiverseAPI.get();

// World snapshots — all return CompletableFuture
mv.getWorld("my_world").thenAccept(opt -> { /* MVWorldSnapshot or empty */ });
mv.getGlobalSpawnWorld().thenAccept(opt -> { /* ... */ });
mv.getAllWorlds().thenAccept(list -> { /* ... */ });
mv.hasMultiverseSpawn("my_world").thenAccept(present -> { /* ... */ });

// Teleport using the spawn-resolution chain
mv.spawn(player).thenAccept(success -> { /* ... */ });

// Plot grid lookups (synchronous — listener-safe)
mv.plotAt(player.getLocation()).ifPresent(coord -> {
    // coord.world(), coord.gridX(), coord.gridZ()
});
mv.plotBounds("city", 3, 5).ifPresent(b -> {
    // b.minX/Z, b.maxX/Z, b.surfaceY, b.size(), b.centerX(), b.centerZ()
});
if (mv.isRoadOrBorder(loc)) { /* loc is on a plot road */ }

// Plot ownership lookups (synchronous — listener-safe)
mv.plotOwnership(loc).ifPresent(o -> {
    // o.ownerUuid(), o.ownerName(), o.mergedGroupId(), o.claimedAt()
});

// Protection check — true if the player can build here under JExMultiverse
// plot rules (owner / trusted / bypass / unclaimed).
boolean allowed = mv.canBuild(player, loc);

// Effective flag value at a location — empty if no plot is claimed there.
mv.getPlotFlag(loc, "pvp").ifPresent(pvpEnabled -> { /* ... */ });
```

The plot-grid + ownership API is intended for external plugins that want to integrate with JExMultiverse plot worlds — claim systems, plot-merging tools, region overlays, anti-grief integrations, etc. It exposes stable `(world, gridX, gridZ)` identifiers, exact bounds, ownership snapshots, and effective flag values without forcing consumers to know the underlying generation parameters or schema.

`MVWorldSnapshot`, `PlotCoord`, `PlotBounds`, and `PlotOwnership` are all Java 21 records.

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
- Schematic loading: Bukkit Structure API + WorldEdit / FastAsyncWorldEdit (optional)
