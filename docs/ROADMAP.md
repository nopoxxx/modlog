# ModLog roadmap

Ideas and planned work that isn't implemented yet. Done items are in git history.

## Reclaiming items on rollback (anti-dupe)

**Problem.** Rolling back an action that *gave* a player items duplicates them, because the player keeps what they got:

- a broken container is restored with its contents (from NBT), but its contents had spilled and were picked up;
- a broken machine with contents (Mekanism, shulker box) drops as an item that keeps its contents;
- a theft from a chest is rolled back (items go back into the chest), but the thief still has them.

**Idea.** When rolling back an action that gave items to someone, restore the victim's side in full, and in parallel try to
take the same items (exact match: item + data components) back from whoever got them:

| What happened to the item         | Reclaimable | How                                                                                                                 |
|-----------------------------------|-------------|---------------------------------------------------------------------------------------------------------------------|
| Still in the player's inventory    | yes         | remove from inventory (online players)                                                                              |
| Dropped on the ground              | yes, until it despawns | remember drop entity UUIDs at break time (`BlockDropsEvent`), remove the entities                         |
| Put into a chest / ME / RS network | yes         | already logged as the player's `added`; rollback runs newest-first, so that deposit is undone first (works today) |
| Given to another player            | partly      | needs pickup logging (below) to know who picked it up; area rollback can then reclaim from them                    |
| Crafted into something else        | no          | ingredients are gone; report only                                                                                    |
| Player offline                     | not yet     | later: edit the player's `playerdata/<uuid>.dat`                                                                    |

**Parts:**

1. ~~Log item pickups and drops by players~~ — done in 0.7.0 (`a:pickup` / `a:drop`; pickups roll back by taking the
   items from the player).
2. Reclaim on rollback of: container `removed`, block breaks of blocks whose drops keep data (machines, shulkers) or whose
   contents spilled (chests), item pickups.
3. Report what couldn't be reclaimed as a separate line, e.g.
   `Not reclaimed: 2 mekanism:metallurgic_infuser from nopox_ (crafted, given away or offline) — possible dupe`.
4. Optional `#strict` flag: don't restore what couldn't be reclaimed (no dupes, but the victim may get back less).
   Open question whether it's needed or the report is enough.

Matching: items with unique data (a machine with contents) match exactly; plain items (diamonds) are fungible, so any
matching stack is taken.

## Backlog

- Create contraptions: drills/saws on a moving contraption break blocks through a different path (movement
  behaviours) and aren't logged yet; only stationary drills/saws are.
- Items absorbed from the ground by blocks (Create basin, hoppers): only the player's drop is logged.
- MySQL/MariaDB backend for large or multi-server setups.
- More events: mob kills, item frames / armor stands, buckets (fluids), chat and commands.
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

