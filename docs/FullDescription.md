# ![](https://raw.githubusercontent.com/mhardin18/copperGolemClerk/main/docs/copper-golem.png) Copper Golem Clerk

**Now hiring: storage clerks.** Must be small, shiny and good with chests. Pay is one copper
block and a pumpkin. Your new clerks sort the way you would: birch logs in with the oak logs,
pickaxes with the tools, whatever your signs say where they say it. Every chest they touch is
left tidy. About 50 ready-made item groups mean they start work the moment you drop the plugin in.

For server owners, the clerks come with good manners. Commands respect GriefPrevention claims,
claim owners get full-chest alerts, golem limits per claim are optional, and nearly every
feature can be tuned in one config file. They never ask for a raise and never complain about the
cobblestone.

## New in 3.1.0

- **Bigger claims, more golems.** Set `claim-blocks-per-extra-golem` and each claim gets one more
  golem for every that many claim blocks. It's off (0) by default.
- **Unclaimed land isn't limited any more.** `max-golems-outside-claims` is retired. Upgrading
  removes it from your `config.yml` and the console tells you. You don't need to do anything.
- **Furnaces come first.** An idle golem tends furnaces before it sorts. Set
  `furnace-jobs-before-sorting` to 0 if you want sorting first, as before.
- **Longer sign labels.** Leave the `[` open and close the `]` on a later line.
- **Search farther.** `search-distance` (default 32, up to 128) sets how far golems look for chests
  and furnaces.
- **Shorter group names.** `templates`, `materials`, `foliage`, `concrete` and `terracotta` replace
  some long ones. Old and new names both work on signs, and your `groups:` are left alone.
- **Smarter `/cgc check`.** It now suggests sign labels for the chest you're looking at.
- **Quicker walks.** Golems give up on a stuck walk sooner. An item with no home goes back in
  its copper chest after about 30 seconds. `matching-chest-timeout-seconds` is retired and
  removed from your `config.yml` on upgrade.

## 🔥 New in 3.0.0: The Furnaces Are Alive

Your clerks have been promoted to the kitchen. Put a sign like `[raw iron]` on a furnace, smoker
or blast furnace, and golems keep it cooking. They bring items to cook from your
`[Furnace Supply]` chest and fuel from your `[Fuel]` chest. Then they carry the ingots, glass
and steak off to wherever they belong. No hopper towers, no waiting around for the
last stack of sand, and nobody burns the coal you were saving. (They don't eat the steak.
We checked.)

- **Fed on time:** a furnace is topped up when it runs low, so it doesn't sit idle while you're
  off mining.
- **Fuel with common sense:** charcoal first, then coal. Lava buckets are used only when there's
  a big pile to cook, so a bucket that lasts 100 items isn't burnt on eight ingots.
- **Lava on tap:** put a cauldron under pointed dripstone with lava above it, and golems refill
  your empty buckets as it fills.
- **Emptied for you:** finished items go into your storage. A hopper under the furnace works too.
- **Only what you label:** unlabelled furnaces are left alone, and nothing from the rest of your
  storage wanders into a fire.

Details are under "Run your furnaces" below.

I'm always open to new ideas for this plugin. Open an enhancement request on GitHub:
https://github.com/mhardin18/copperGolemClerk/issues

Bug reports go there too, though you won't need one: there are no bugs. None. You can trust me
on that. (If a golem does something odd anyway, it's a feature I haven't documented yet. I'd
love to hear about it.)

---

## 🎓 What your golems learn

### 🧩 Sort by kind, not just by exact item
A golem carrying spruce planks uses your chest of oak planks. Stews go with the food,
swords with the weapons, every colour of wool together. Groups are plain lists in the
config, so you can change them or make your own.

### 🏷️ Read signs and item frames
Write `[logs]`, `[ores]`, `[oak log]`, `[*_ORE]` or `[#planks]` on a sign on a chest, or
hang an item in a frame, and that's what goes there. When several chests would take an
item, the most specific one wins. `[stews]` beats `[food]` for a bowl of stew.

| Sign | Means |
|---|---|
| `[logs]` | anything in the logs group |
| `[oak log]` | just oak logs |
| `[*_ORE]` | every ore block (`*` = any letters) |
| `[*sand]` | items whose name ends in sand: sand, red sand, soul sand |
| `[#planks]` | everything in a Minecraft item tag (`#` = a tag) |
| `[*]` | any item at all (picked before `[Overflow]`, so use `[Overflow]` for a catch-all) |
| `[Overflow]` | a catch-all for things with no home |
| `[No Golems]` | golems keep out |
| `[Only Logs]` | on a copper chest: golems take only the logs out |

**`*` and `#` on a sign.** A `*` stands for any letters, or none, in the item's name. The whole
name has to fit.
- `[*sand]` ends in sand: sand, red sand, soul sand, but not sandstone.
- `[sand*]` starts with sand: sand, sandstone, and its slabs and stairs.
- `[*sand*]` contains sand.
- `[*]` alone is every item.

A `#` names a Minecraft item tag, such as `[#logs]` or `[#minecraft:logs]`. Datapack tags work
too. So do `[@edible]`, `[@fuel]` and `[@record]`.

### ♻️ An overflow chest that empties itself
Items with no home land in your `[Overflow]` chest. Add a chest for them later and idle
golems carry them over by themselves. If a hopper fills the overflow chest (a smelter's
output, say), golems wait until 8 of an item have built up, or 2 minutes. They don't walk
over for every ingot.

### 🗄️ Bigger storage rooms
- Golems **tidy** as they go: partial stacks are merged and like items kept together.
- They **step aside** for each other in one-wide aisles instead of getting stuck.

Each of these is an optional setting:
- Reach chests stacked up to **6 high** (vanilla: 2).
- Look up to **128 blocks** away for chests (vanilla: 32).
- Carry up to **64 items** a trip (vanilla: 16).
- Use **barrels and shulker boxes** as well as chests.

### 🔥 Run your furnaces
Label a furnace `[raw iron]` and golems keep it cooking. Items to cook come from your
`[Furnace Supply]` chest. Fuel comes from your `[Fuel]` chest, enough for what's waiting plus
the next load. What's done gets sorted away. Nothing else from your storage goes in a furnace.

**What to write on a furnace.** Any label a chest takes also works on a furnace, a smoker or a
blast furnace, as a sign or an item frame. Golems only put in what that kind of furnace
can cook, so a broad label is safe. A smoker labelled `[food]` gets raw meat and fish, never
bread.

| Label on the furnace | What golems put in | Example |
| --- | --- | --- |
| `[*]` | anything that furnace can cook | a furnace: ores, sand, logs, raw food. A smoker: raw food only |
| A group | the group's items that it can cook | `[ores]` ore blocks (Silk Touch) and ancient debris · `[ingots and gems]` raw iron, gold and copper · `[food]` raw meat, fish, potatoes · `[logs]` logs and wood (charcoal) · `[stone]` cobblestone and stone bricks · `[dirt and sand]` sand, red sand, clay |
| One item | just that item | `[raw iron]` · `[sand]` · `[cobblestone]` · `[clay ball]` · `[wet sponge]` · `[cactus]` |
| A pattern | items whose names match | `[raw_*]` raw metal · `[*_ore]` ore blocks · `[*log]` logs |
| A tag | items in a Minecraft tag | `[#minecraft:logs]` · `[#minecraft:sand]` |
| An item frame | the framed item | a frame holding raw gold |

Don't put these on a furnace. `[Furnace]` is the furnace item, which nothing cooks. `[Fuel]` and
`[Furnace Supply]` go on chests. `/cgc check` on a furnace lists exactly what its label lets in.

**Signs for the chests that feed it.**

| Sign on a chest or barrel | What it does | Spellings that work (any case) |
| --- | --- | --- |
| `[Furnace Supply]` | golems take items to cook from it | `[Furnace Supply]`, `[Furnace Supplies]`, `[Furnaces Supply]`, `[Furnace-Supply]`, `[Furnace_Supply]` |
| `[Fuel]` | golems take fuel from it: charcoal, then coal, then lava buckets | `[Fuel]`, `[Fuels]` |

Lava buckets go in only when a furnace has 100 or more items to cook. Empty buckets go back to
the `[Fuel]` chest and wait for a lava cauldron that dripstone refills.

Neither chest receives sorted items unless it also has an item label. Put both on one sign:
`[Fuel] [charcoal]` or `[Furnace Supply] [raw iron]`.

### 🦺 Look after themselves
- A golem that falls down a shaft or wanders off **walks back home**. One that's stuck gets
  reported, with where it is.
- Golems **keep off trapdoors** and **stay out of portals**.
- Every copper golem death is written to the server log with its cause, so you can see what
  happened to a golem that went missing.

---

## 🎮 For players

- **`/cgc find diamond`** lists the chests near you that hold diamonds, nearest first, including
  diamonds inside shulker boxes. The nearest few glow for a few seconds.
  - It looks 32 blocks around you. Add a number to look farther: `/cgc find diamond 60`, up to 64.
  - By default it only looks in your own GriefPrevention claims, so nobody can use it to snoop.
  - Search `torch` and it also tells you about the copper and soul torches nearby.
- **`/cgc check`** explains a chest you can open: what its labels mean and what it takes.
  Looking at nothing, it says which groups the item in your hand belongs to.
- **`/cgc overflow`** shows what's piling up in the overflow chests near you, and what label
  would give each item a home.
- **`/cgc label`** suggests what to write on a chest's sign, from what's in it.
- **`/cgc storage`** shows how full the storage around you is, by label, fullest first.
- **`/cgc stats`** shows what golems have sorted in your claims today, in the last 7 days and
  all time.
- **`/cgc golems`** lists your own golems and where they are, if the server allows it.
  `/cgc golems 50` lists only those within 50 blocks on each axis, the same way find counts.
- **`/cgc help`** lists the commands. `/cgc help <command>` explains one.
- **Hold bamboo** to see a label over each golem saying what it's doing ("carrying 16 birch
  log", "fetching granite", "lost, walking home") and how weathered it is.
- **Right-click a golem with bamboo** to switch that one golem back to plain vanilla sorting.
- In a GriefPrevention claim you **get told** when one of your labelled chests is full, even
  if you were offline at the time. You also get a summary of what your golems sorted while you
  were away.

## 🛠️ For admins

- `/cgc check` explains any chest: what its labels mean, what it takes, and anything
  stopping golems from using it.
- `/cgc overflow` lists what's in overflow chests and what label would give each item a home.
- `/cgc golems` shows where the golems are, what they're carrying and whether any are lost.
  Add a radius to list only those nearby.
- `/cgc stats` shows how much the golems have sorted, server-wide.
- Players only see what they could open themselves. Admins can see into every claim.
- Optional golem limits cap how many golems in one claim use the plugin. Bigger claims can get
  more. Off by default.
- Load tested with up to 200 golems. A working golem costs about 0.1 ms per tick, the same as
  in vanilla.
- Nearly every feature can be switched off or tuned in `config.yml`. `/cgc reload`
  applies changes without a restart.
- Only copper golems are affected. Iron and snow golems, villagers and other mobs are left
  alone.

---

## ▶️ Getting started

1. Drop the jar into `plugins/` and restart.
2. Place a copper chest and fill it with mixed items.
3. Place some chests within 32 blocks sideways and 8 up or down. Put one item in each to say
   what it's for, or add a sign such as `[logs]`.
4. Build a copper golem (a carved pumpkin on a copper block) next to the copper chest.
5. Stay nearby, since golems only work in loaded chunks, and watch them file everything.

## ✅ Requirements

- **Paper 26.3**, build 190 or newer. Spigot and Bukkit aren't supported, because the plugin
  uses Paper's golem events.
- **Java 25**
- Recommended: **GriefPrevention**.
  - With it, alerts go to the claim's owner, players' commands stay within their own claims,
    and only players who may build somewhere can switch golems there.
  - Without it, alerts go to ops, and players' `/cgc find` finds nothing until an admin allows it.

## 🔑 Permissions

All permissions are standard nodes, so they work with LuckPerms or any other permissions plugin.

| Permission | Default | Allows |
|---|---|---|
| `coppergolemclerk.find`, `.check`, `.label`, `.overflow`, `.storage`, `.stats` | everyone | the player commands, limited to their own claims or what they could open |
| `coppergolemclerk.golems` | everyone | `/cgc golems` for their own golems, if the server turns it on |
| `coppergolemclerk.toggle` | everyone | switching a golem to vanilla and back |
| `coppergolemclerk.allclaims` | op | the commands above see into every claim |
| `coppergolemclerk.alerts` | op | alerts about things outside any claim, where there's no owner to tell |
| `coppergolemclerk.admin` | op | all `/cgc` commands |

## 🌐 Links

- Full documentation and every setting: [README on GitHub](https://github.com/mhardin18/copperGolemClerk#readme)
- Changelog: [CHANGELOG.md](https://github.com/mhardin18/copperGolemClerk/blob/main/CHANGELOG.md)
- Bugs and ideas: [GitHub issues](https://github.com/mhardin18/copperGolemClerk/issues)
- Licence: GPL-3.0, source on [GitHub](https://github.com/mhardin18/copperGolemClerk)
