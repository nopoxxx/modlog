# ModLog

Server-side block and storage logging with lookup and rollback for **NeoForge 1.21.1** — like CoreProtect, but aware
of modded inventories: AE2 and Refined Storage networks, Mekanism machines, Create, Sophisticated Backpacks and any
block that exposes an item handler.

Clients don't need the mod. It also runs on [Arclight](https://github.com/IzzelAliz/Arclight) (NeoForge + Bukkit).

## Features

**Logging**
- Blocks broken and placed by players, mobs and fake players (machines acting as a player), with the full block entity
  data of broken blocks (a chest's contents, a machine's state) and what fell out of them.
- Explosions, attributed to the player who lit or placed the TNT when known.
- Items put into and taken out of any block inventory — vanilla or modded, including AE2/RS terminals, wireless
  terminals and backpacks. It works by comparing the player's own items before and after each container interaction,
  so it doesn't need per-mod GUI hooks.
- Items players drop and pick up, and items backpack magnet/pickup upgrades pull in.
- Create mechanical drills and saws.

Actions are confirmed at the end of the tick, so actions cancelled by other mods or Bukkit plugins aren't logged.

**Looking things up**
- `/ml i` inspector: left-click a block for its history, right-click a container for its transactions.
- `/ml l` searches by player, time, area, action and block/item.
- `/ml gui` opens an in-game log browser (a chest menu) with filters, paging, teleport and one-click rollback.

**Rolling back**
- `/ml rollback` and `/ml restore` for blocks (with their contents), container transactions, AE2/RS network
  transactions, backpacks and pickups; `/ml undo` reverts the last one.
- Never overwrites a block it didn't log; works in slices per tick to avoid lag.
- **Anti-dupe:** a rollback takes back what the rolled-back actions gave out — items taken from containers and drops of
  broken blocks — from the ground or the player's inventory, following items the same rollback already moved. Anything
  it can't find is reported as a possible duplicate.

## Supported mods

| Mod | What's covered |
|---|---|
| Applied Energistics 2 | Terminals, crafting/pattern terminals, ME chest, wireless terminals: logged and rolled back through the ME network |
| Refined Storage 2 | Grids, crafting/pattern grids, wireless grid: logged and rolled back through the RS network |
| Mekanism | All machine slots, as a player in the GUI sees them (automation can't fill energy slots or take from inputs) |
| Create | Depots, basins, vaults (generic), mechanical drill and saw block breaking |
| Sophisticated Backpacks | Backpacks opened as items, upgrade slots, magnet and pickup upgrades |
| Any other mod | Block inventories through NeoForge's item capability, GUIs through the generic tracking |

Integrations load only when their mod is installed. Tested versions: AE2 19.2.18, RS 2.0.0, Mekanism 10.7.14,
Create 6.0.6, Sophisticated Backpacks 3.25.77 / Core 1.4.86.

## Installation

1. Requires Minecraft 1.21.1 and NeoForge 21.1.192 or newer.
2. Put `modlog-<version>.jar` into the server's `mods` folder. SQLite is bundled; data goes to `modlog/modlog.db`.
3. Configure in `config/modlog-common.toml` if needed.

## Commands

`/ml` (alias `/modlog`).

| Command | Permission | Description |
|---|---|---|
| `/ml i` | `modlog.lookup` | Toggle the inspector |
| `/ml l <params>` | `modlog.lookup` | Search; `/ml page <n>` for more pages |
| `/ml gui [u:<players>] [b:<blocks>]` | `modlog.lookup` | In-game log browser |
| `/ml status` | `modlog.lookup` | Database status |
| `/ml rollback <params>` (`rb`) | `modlog.rollback` | Undo matching actions; needs `t:` and `u:` or `r:` |
| `/ml restore <params>` (`rs`) | `modlog.rollback` | Redo rolled-back actions |
| `/ml undo` | `modlog.rollback` | Revert your last rollback/restore |
| `/ml purge t:<age> [vacuum] [confirm]` | `modlog.purge` | Delete actions older than `<age>`; without `confirm` it only counts them |

Permissions go through NeoForge's permission API, so LuckPerms for NeoForge works out of the box. On Arclight, a
node set explicitly through Bukkit (LuckPerms for Bukkit) takes precedence. Without a permissions plugin the defaults
are operator levels: `lookup` 2, `rollback` 3, `purge` 4. The console always has full access.

Parameters:

| Parameter | Example | Meaning |
|---|---|---|
| `u:` | `u:Steve,Alex`, `u:#tnt`, `u:#creeper` | Players, or non-player sources prefixed with `#` |
| `t:` | `t:30m`, `t:2h30m`, `t:7d` | Time window (`s`, `m`, `h`, `d`, `w`) |
| `r:` | `r:10`, `r:#world`, `r:#global` | Radius around you, the current dimension, or everywhere |
| `a:` | `a:-block`, `a:container` | `block`, `+block`, `-block`, `container`, `+container`, `-container`, `item`, `pickup`, `drop` |
| `b:` | `b:chest,ae2:drive` | Blocks or items (`minecraft:` is implied) |

To clean up after a player, roll back by player without a radius: `/ml rb u:Griefer t:2h`. A radius limits the
rollback to that area, and items carried out of it won't be followed.

## Configuration

`config/modlog-common.toml`:

| Key | Default | |
|---|---|---|
| `database.sqliteFile` | `modlog/modlog.db` | Database file, relative to the server directory |
| `database.flushIntervalMs` | `500` | How often queued actions are written |
| `lookup.pageSize` | `7` | Lines per page in chat |
| `rollback.actionsPerTick` | `500` | Lower it if large rollbacks lag the server |
| `rollback.maxActions` | `200000` | Larger rollbacks are refused |
| `rollback.reclaimItems` | `true` | Anti-dupe (see above) |
| `logging.blockBreak`, `blockPlace`, `explosions`, `containers`, `itemDrops`, `itemPickups` | `true` | What to log |

## Building

```sh
./gradlew build
```

Needs JDK 21. The jar ends up in `build/libs/`. Integrations compile against the mods from Modrinth's Maven
(`https://api.modrinth.com/maven`); they're not bundled.

Releases: pushing a tag like `v0.9.0` builds the jar with that version and attaches it to a GitHub release.

## Limitations

See [docs/ROADMAP.md](docs/ROADMAP.md) for what's planned. Notably: Create contraptions (moving drills) aren't
logged, items can't be taken back from offline players, and only SQLite is supported for now.

## License

[LGPL-3.0](LICENSE) (with the [GPL-3.0](COPYING) it builds on).
