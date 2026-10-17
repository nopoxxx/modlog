# ModLog roadmap

Ideas and planned work that isn't implemented yet. Done items are in git history.

## Reclaiming items on rollback (anti-dupe)

Done in 0.8.x (see `rollback/Reclaimer`). Open points:

- `#strict` flag: don't restore what couldn't be taken back (no dupes, but the victim may get back less).
- Area rollbacks (`r:`) only undo what happened inside the area; items carried out of it are reported, not chased.

## Backlog

- Waterlogging (buckets into slabs/stairs) isn't logged; only plain fluid blocks are.
- Entities in unloaded chunks (item frames, armor stands) can't be rolled back until the chunk is loaded.
- Items absorbed from the ground by blocks (Create basin, hoppers): only the player's drop is logged.
- MySQL/MariaDB backend for large or multi-server setups.
- Slow JVM exit after `stop` on the Arclight test server (not caused by ModLog; investigate with a thread dump).

- **Interactions (like CoreProtect `a:click`)**: log who used buttons, levers, doors, trapdoors, fence gates,
  pressure plates, note blocks, repeaters/comparators (setting changes), and modded equivalents.
  - Hook: `PlayerInteractEvent.RightClickBlock` (LOWEST, not cancelled), confirmed at end of tick like
    block actions: log only if the block state actually changed (e.g. `powered`, `open`, repeater `delay`),
    so plain right-clicks on stone aren't logged. That catches modded blocks with a state change too.
  - New action type `INTERACT` ("clicked" / "toggled"), material = block id, data = state before→after.
  - Lookup/inspector only by default; rollback could restore the previous state (repeater delay,
    lever position), but must not fire redstone updates — undecided.
  - Config `logging.interactions`, filter `a:click`; inspector left/right-click shows them with block actions.
  - Pressure plates/tripwires are triggered by walking, not clicking: separate optional log (noisy).

