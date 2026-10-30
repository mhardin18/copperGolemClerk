# Copper Golem Clerk

**Your copper golems, promoted to storage clerks.**

Throw your loot into a copper chest and walk away. Your golems file it all: birch logs in
with the oak logs, the diamond pickaxe with the tools, raw iron into the blast furnace and
the ingots back out to your iron chest. They pick the right chest, walk straight to it,
keep it tidy, and let you know when it's full.

No commands to learn and nothing to set up: about 50 item groups covering every survival
item are ready out of the box. Add a sign to a chest when you want more control.

---

## What your golems learn

### 📦 Sort by kind, not just by exact item
A golem carrying spruce planks uses your chest of oak planks. Stews go with the food,
swords with the weapons, every colour of wool together. Groups are plain lists in the
config, so you can make your own.

### 🏷️ Read signs and item frames
Write `[logs]`, `[ores]`, `[oak log]`, `[*_ORE]` or `[#planks]` on a sign on a chest, or
hang an item in a frame, and that's what goes there. When several chests would take an
item, the most specific one wins: `[stews]` beats `[food]` for a bowl of stew.

| Sign | Means |
|---|---|
| `[logs]` | anything in the logs group |
| `[oak log]` | just oak logs |
| `[*_ORE]` | every ore |
| `[#planks]` | everything in a Minecraft tag |
| `[Overflow]` | a catch-all for things with no home |
| `[No Golems]` | golems keep out |
| `[Only Logs]` | on a copper chest: golems take only the logs out |

### ♻️ An overflow chest that empties itself
Items with no home land in your `[Overflow]` chest. Add a chest for them later and idle
golems carry them over by themselves.

### 🔥 Run your furnaces
Put `[raw iron]` on a blast furnace or `[food]` on a smoker (it only gets the raw food it can cook). Golems bring what it
cooks, keep it topped up with coal from your storage, and sort the results.
Furnaces without a label are never touched.

### 🏗️ Big storage rooms
- Reach chests stacked up to **6 high** (vanilla: 2).
- Use **barrels and shulker boxes** too, if you like.
- Carry up to **64 items** a trip instead of 16.
- **Step aside** for each other in one-wide aisles instead of getting stuck.
- **Tidy** as they go: partial stacks merged, like items kept together.

### 🧭 Look after themselves
- A golem that falls down a shaft or wanders off **walks home**, or you're told where it
  is (or it's teleported back, if you prefer).
- Golems **avoid trapdoors** and **stay out of portals**.
- Every golem death is logged with its cause, so no golem goes missing unexplained.

---

## For players

- **`/cgc find diamond`:** lists the chests near you that hold diamonds, nearest first
  (including diamonds inside shulker boxes), and makes them glow for a few seconds.
  You only see chests you could open yourself: **GriefPrevention claims are respected**.
- **Hold bamboo** to see a label over each golem saying what it's doing ("carrying 32 birch
  log", "taking raw iron to a furnace", "lost, walking home") and how weathered it is.
- **Right-click a golem with bamboo** to switch that one golem back to plain vanilla.
- **Get told** when one of your chests is full, even if you were offline at the time, and
  get a summary of what your golems sorted while you were away.

## For admins

- `/cgc check` explains any chest: what its labels mean, what it takes, and anything
  stopping golems from using it.
- `/cgc overflow` lists what's in your overflow chests and what label would give each
  item a home.
- `/cgc golems` shows where every golem is, what it's carrying and whether it's lost.
- `/cgc stats` shows how much the golems have sorted.
- Every feature can be switched off or tuned in `config.yml`; `/cgc reload` applies changes
  without a restart.
- Only copper golems are ever affected. Iron and snow golems, villagers and other mobs are
  left alone.

---

## Getting started

1. Drop the jar into `plugins/` and restart.
2. Place a copper chest and fill it with mixed items.
3. Place some chests within 32 blocks. Put one item in each to say what it's for, or add a
   sign such as `[logs]`.
4. Build a copper golem (a carved pumpkin on a copper block) next to the copper chest.
5. Stay nearby (golems only work in loaded chunks) and watch them file everything.

## Requirements

- **Paper 26.3** (Spigot and Bukkit aren't supported: the plugin uses Paper's golem events)
- **Java 25**
- Optional: **GriefPrevention**, for claim-aware alerts, `/cgc find` and golem switching

## Permissions

| Permission | Default | Allows |
|---|---|---|
| `coppergolemclerk.admin` | op | all `/cgc` commands |
| `coppergolemclerk.find` | everyone | `/cgc find` |
| `coppergolemclerk.find.allclaims` | op | `/cgc find` into other players' claims |
| `coppergolemclerk.toggle` | everyone | switching a golem to vanilla and back |
| `coppergolemclerk.alerts` | op | alerts about unclaimed places |

## Links

- Full documentation and every setting: [README on GitHub](https://github.com/mhardin18/copperGolemClerk#readme)
- Changelog: [CHANGELOG.md](https://github.com/mhardin18/copperGolemClerk/blob/main/CHANGELOG.md)
- Bugs and ideas: [GitHub issues](https://github.com/mhardin18/copperGolemClerk/issues)
- Licence: GPL-3.0, source on [GitHub](https://github.com/mhardin18/copperGolemClerk)
