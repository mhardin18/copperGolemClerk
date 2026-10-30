# Copper Golem Clerk

**Your copper golems, promoted to storage clerks.**

Throw your loot into a copper chest and walk away. Your golems file it all: birch logs in
with the oak logs, the diamond pickaxe with the tools, and the mixed loot from your last
mining trip into a dozen tidy chests. They pick the right chest, walk straight to it,
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

### 🏗️ Big storage rooms
- Reach chests stacked up to **6 high** (vanilla: 2).
- Use **barrels and shulker boxes** too, if you like.
- Carry up to **64 items** a trip instead of 16.
- **Step aside** for each other in one-wide aisles instead of getting stuck.
- **Tidy** as they go: partial stacks merged, like items kept together.

### 🧭 Look after themselves
- A golem that falls down a shaft or wanders off **walks home**, and if it's stuck you're
  told where it is.
- Golems **avoid trapdoors** and **stay out of portals**.
- Every golem death is logged with its cause, so no golem goes missing unexplained.

---

## For players

- **`/cgc find diamond`:** lists the chests in your claims that hold diamonds, nearest
  first (including diamonds inside shulker boxes), and makes the nearest few glow for a few seconds.
  **By default it only looks in your own GriefPrevention claims**, so nobody can use it to
  snoop (a server can widen it to chests you've been trusted with, never further).
- **`/cgc check`** explains a chest you can open, **`/cgc overflow`** shows what's piling
  up in your overflow chests, and **`/cgc stats`** shows what your golems have sorted.
- **Hold bamboo** to see a label over each golem saying what it's doing ("carrying 32 birch
  log", "fetching granite", "lost, walking home") and how weathered it is.
- **Right-click a golem with bamboo** to switch that one golem back to plain vanilla.
- **Get told** when one of your chests is full, even if you were offline at the time, and
  get a summary of what your golems sorted while you were away.

## For admins

- `/cgc check` explains any chest: what its labels mean, what it takes, and anything
  stopping golems from using it.
- `/cgc overflow` lists what's in overflow chests and what label would give each item a home.
- `/cgc golems` shows where every golem is, what it's carrying and whether it's lost
  (players can be allowed to list their own).
- `/cgc stats` shows how much the golems have sorted, server-wide.
- Admins see into every claim; players only ever see what they could open themselves.
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
| `coppergolemclerk.find`, `.check`, `.overflow`, `.stats` | everyone | the player commands, limited to their own claims / what they could open |
| `coppergolemclerk.golems` | everyone | `/cgc golems` for their own golems, if the server turns it on |
| `coppergolemclerk.toggle` | everyone | switching a golem to vanilla and back |
| `coppergolemclerk.allclaims` | op | the commands above see into every claim |
| `coppergolemclerk.alerts` | op | alerts about things outside any claim, where there's no owner to tell |
| `coppergolemclerk.admin` | op | all `/cgc` commands |

## Links

- Full documentation and every setting: [README on GitHub](https://github.com/mhardin18/copperGolemClerk#readme)
- Changelog: [CHANGELOG.md](https://github.com/mhardin18/copperGolemClerk/blob/main/CHANGELOG.md)
- Bugs and ideas: [GitHub issues](https://github.com/mhardin18/copperGolemClerk/issues)
- Licence: GPL-3.0, source on [GitHub](https://github.com/mhardin18/copperGolemClerk)
