# Copper Golem Clerk

**Now hiring: storage clerks.** Must be small, shiny and good with chests. Pay is one copper
block and a pumpkin. Your new clerks sort the way you would: birch logs in with the oak logs,
pickaxes with the tools, whatever your signs say where they say it, and every chest they touch
left tidy. About 50 ready-made item groups mean they start work the moment you drop the plugin in.

For server owners, the clerks come with good manners: commands that respect GriefPrevention
claims, full-chest alerts for claim owners, optional golem limits per claim, and nearly
every feature tunable in one config file. They never ask for a raise and never complain about the
cobblestone.

I'm always open to new ideas for this plugin. Open an enhancement request on GitHub:
https://github.com/mhardin18/copperGolemClerk/issues

---

## What your golems learn

### 📦 Sort by kind, not just by exact item
A golem carrying spruce planks uses your chest of oak planks. Stews go with the food,
swords with the weapons, every colour of wool together. Groups are plain lists in the
config, so you can change them or make your own.

### 🏷️ Read signs and item frames
Write `[logs]`, `[ores]`, `[oak log]`, `[*_ORE]` or `[#planks]` on a sign on a chest, or
hang an item in a frame, and that's what goes there. When several chests would take an
item, the most specific one wins: `[stews]` beats `[food]` for a bowl of stew.

| Sign | Means |
|---|---|
| `[logs]` | anything in the logs group |
| `[oak log]` | just oak logs |
| `[*_ORE]` | every ore block |
| `[#planks]` | everything in a Minecraft item tag |
| `[Overflow]` | a catch-all for things with no home |
| `[No Golems]` | golems keep out |
| `[Only Logs]` | on a copper chest: golems take only the logs out |

### ♻️ An overflow chest that empties itself
Items with no home land in your `[Overflow]` chest. Add a chest for them later and idle
golems carry them over by themselves.

### 🗄️ Bigger storage rooms
- **Tidy** as they go: partial stacks merged, like items kept together.
- **Step aside** for each other in one-wide aisles instead of getting stuck.
- Optional, with a setting:
  - reach chests stacked up to **6 high** (vanilla: 2);
  - carry up to **64 items** a trip (vanilla: 16);
  - use **barrels and shulker boxes** as well as chests.

### 🦺 Look after themselves
- A golem that falls down a shaft or wanders off **walks back home**; one that's stuck gets
  reported, with where it is.
- Golems **keep off trapdoors** and **stay out of portals**.
- Every copper golem death is written to the server log with its cause, so you can see what
  happened to a golem that went missing.

---

## For players

- **`/cgc find diamond`:** lists the chests near you that hold diamonds, nearest first
  (including diamonds inside shulker boxes), and the nearest few glow for a few seconds.
  By default it only looks in your own GriefPrevention claims, so nobody can use it to snoop.
- **`/cgc check`** explains a chest you can open, **`/cgc overflow`** shows what's piling
  up in your overflow chests, and **`/cgc stats`** shows what golems have sorted in your claims.
- **Hold bamboo** to see a label over each golem saying what it's doing ("carrying 16 birch
  log", "fetching granite", "lost, walking home") and how weathered it is.
- **Right-click a golem with bamboo** to switch that one golem back to plain vanilla sorting.
- In a GriefPrevention claim you **get told** when one of your labelled chests is full (even
  if you were offline at the time), and get a summary of what your golems sorted while you
  were away.

## For admins

- `/cgc check` explains any chest: what its labels mean, what it takes, and anything
  stopping golems from using it.
- `/cgc overflow` lists what's in overflow chests and what label would give each item a home.
- `/cgc golems` shows where the golems are, what they're carrying and whether any are lost.
- `/cgc stats` shows how much the golems have sorted, server-wide.
- Players only see what they could open themselves; admins can see into every claim.
- Optional golem limits cap how many golems in one claim use the plugin (off by default).
- The plugin adds no measurable tick time: a working copper golem costs about 0.1 ms per tick
  with or without it (measured with up to 200 golems).
- Nearly every feature can be switched off or tuned in `config.yml`, and `/cgc reload`
  applies changes without a restart.
- Only copper golems are affected. Iron and snow golems, villagers and other mobs are left
  alone.

---

## Getting started

1. Drop the jar into `plugins/` and restart.
2. Place a copper chest and fill it with mixed items.
3. Place some chests within 32 blocks sideways and 8 up or down. Put one item in each to say
   what it's for, or add a sign such as `[logs]`.
4. Build a copper golem (a carved pumpkin on a copper block) next to the copper chest.
5. Stay nearby (golems only work in loaded chunks) and watch them file everything.

## Requirements

- **Paper 26.3**, build 190 or newer (Spigot and Bukkit aren't supported: the plugin uses Paper's golem events)
- **Java 25**
- Recommended: **GriefPrevention**. Alerts then go to the claim's owner, players' commands
  stay within their own claims, and only players who may build somewhere can switch golems
  there. Without it, alerts go to ops, and players' `/cgc find` finds nothing until an
  admin allows it.

## Permissions

All permissions are standard nodes, so they work with LuckPerms or any other permissions plugin.

| Permission | Default | Allows |
|---|---|---|
| `coppergolemclerk.find`, `.check`, `.overflow`, `.stats` | everyone | the player commands, limited to their own claims or what they could open |
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
