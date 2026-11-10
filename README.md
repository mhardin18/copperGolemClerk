# Copper Golem Clerk

**Copper golems that sort your storage.**

Drop anything into a copper chest and your golems file it away. Birch logs go in with the
oak logs, a diamond pickaxe goes to the tools, and the mixed loot from your last mining
trip ends up in a dozen tidy chests. Label a chest with a sign like `[logs]` or `[Ores]`,
or put one item in it to say what it's for. Golems pick the right chest, walk straight to
it, keep the chests tidy, and tell you when one is full.

This is a Paper plugin for **Paper 26.3**, build 190 or newer (Java 25). It works out of
the box with about 50 item groups that cover nearly every survival item. Everything else
is optional.

## Features

**Sorting that understands your storage**
- **Groups:** a golem carrying birch logs uses the chest of oak logs. A stew goes with the
  food, a pickaxe with the tools. About 50 groups ship ready to use. You can edit them or
  add your own.
- **Sign and item frame labels:** `[logs]`, `[oak log]`, `[*_ORE]`, `[#planks]`, or an item
  in a frame. The most specific chest wins: `[stews]` beats `[food]` for a stew.
- **`[Overflow]` chests** catch anything without a home. Idle golems move items back out
  once you add a chest for them.
- **`[No Golems]`** keeps golems out of a chest. **`[Only ...]`** on a copper chest makes
  golems take only some items out of it.
- Golems walk straight to the right chest instead of dropping things in the first
  empty one. They put back what they can't place.
- **Tidy chests:** partial stacks are merged and like items kept together.

**Bigger, faster storage rooms**
- **Taller stacks** (a setting): golems reach chests up to 6 rows high (vanilla: 2).
- **Bigger storage areas** (a setting): golems look up to 128 blocks away for chests (vanilla: 32).
- **Barrels and shulker boxes** as destinations (a setting).
- **Bigger loads** (a setting): carry up to 64 items a trip instead of 16.
- **Golems run your furnaces:** label a furnace `[raw iron]`. Golems feed it from your
  `[Furnace Supply]` chest, fuel it from your `[Fuel]` chest and sort what comes out.
- **Golems give way** to each other in narrow aisles instead of getting stuck.

**Golems that look after themselves**
- **Lost golems walk home**, and you're told where one is if it's stuck.
- Golems **keep off trapdoors** and **stay out of portals**.
- Every golem death is logged with its cause.

**For players**
- **`/cgc find <item> [radius]`:** which chests near you hold something. The nearest glow.
  By default players only search their own GriefPrevention claims.
- **`/cgc check`:** explains a chest you could open and suggests a sign label from what's in
  it. Without a chest, it says which groups the item in your hand is in.
- **`/cgc overflow`:** what's piling up in the overflow chests near you, and what label would
  give each item a home.
- **`/cgc label`:** what to write on a chest's sign, from what's in it.
- **`/cgc storage`:** how full the storage around you is, by label, fullest first.
- **`/cgc stats`:** what golems have sorted in your claims today, in the last 7 days and all time.
- **`/cgc golems [radius]`:** your own golems and where they are (only with `golems-command-for-players`).
- **`/cgc help`:** the commands. `/cgc help <command>` explains one.
- **Full-chest alerts** go to the claim owner, even if they're offline.
- **Status labels:** hold bamboo to see what each golem is doing.
- A **join summary** of what golems sorted while you were away.
- Right-click a golem with bamboo to switch it **back to vanilla**.

**For admins**
- **`/cgc check`** explains any chest: what its labels mean, what it takes, what stops
  golems using it, and what sign label to give it.
- **`/cgc overflow`** lists what's in overflow chests and what label would give each item a home.
- **`/cgc stats`** shows how much the golems have sorted, server-wide.
- **`/cgc golems`** finds every golem: where it is, what it's carrying and whether it's lost.
- **Golem limits** (optional, off by default) cap how many golems in one claim use the
  plugin.
- **Load tested** with up to 200 golems. A working golem costs about 0.1 ms per tick, the
  same as in vanilla.

Every feature has a setting. See [Settings reference](#settings-reference).

## Requirements

- A [Paper](https://papermc.io/downloads/paper) server, version **26.3**, build **190** or newer.
  That build added the golem sorting event. On older builds the plugin logs why and turns itself off.
- Java **25**

Spigot and Bukkit aren't supported. The plugin uses Paper-only golem events.

## Install

1. Download `CopperGolemClerk-<version>.jar` from the
   [latest release](https://github.com/mhardin18/copperGolemClerk/releases/latest).
   Release tags name the Paper version they're for (for example `v1.0.0-paper26.3`).
2. Put the jar in your server's `plugins/` folder.
3. Restart the server (don't use `/reload`).
4. Check the console for `Enabling CopperGolemClerk`, or run `/plugins` in game.

On first start the plugin creates `plugins/CopperGolemClerk/config.yml`. The defaults work
as they are: about 50 groups that cover nearly every survival item. After editing the file,
run `/cgc reload` to apply it without a restart.

### Updating

Replace the old jar with the new one and restart. Your `config.yml` is kept and brought up
to date at startup:

- Settings that are new in that version are added, each with its explaining comment, in the
  same place as in a fresh config.
- The comment above every other setting is replaced with the new version's explanation.
- Your values are never changed.
- A note at the end of a setting's line (`carry-amount: 32  # our farm`) stays. A comment on
  its own line above a setting is replaced.
- The old file is saved as `config-before-<version>.yml`.
- The console lists what was added and which explanations changed.

If the plugin can't write `config.yml` (a permissions problem), the console says so and new
settings use their defaults. A `config.yml` with a YAML error is left alone.

### Uninstalling

Delete the jar. Delete the `plugins/CopperGolemClerk/` folder too if you don't want to keep
the config.

### Upgrading from SmartCopperGolems

This plugin was called SmartCopperGolems until version 2.0. To upgrade:

1. Delete `SmartCopperGolems-*.jar` from `plugins/`.
2. Add `CopperGolemClerk-<version>.jar`.
3. Restart.

On first start, your `config.yml` is copied from `plugins/SmartCopperGolems/` to
`plugins/CopperGolemClerk/`. After that you can delete the old folder.

The command is now `/cgc` (or `/coppergolemclerk`). The permission is now
`coppergolemclerk.admin`. If you granted `smartcoppergolems.admin` to anyone, grant the new
one instead.

### Quick start

1. Place a copper chest and fill it with mixed items.
2. Place a few chests nearby (within 32 blocks sideways, 8 up or down). Put one
   item in each to say what goes there, or label it with a sign like `[logs]` or
   an item frame.
3. Build a copper golem (a carved pumpkin on top of a copper block) next to the
   copper chest.
4. Stay nearby. Golems only work in loaded chunks, and Paper slows them down
   when no player is close.

The golem now puts birch logs in your oak log chest, a diamond pickaxe in your
tools chest, and so on.

## How it works

A copper golem takes items out of a copper chest and looks for a chest to put them in.
On its own it only uses a chest that already holds that exact item, or an empty one.

With this plugin it also uses a chest whose items share a **group** with what it
carries, or a chest labelled for it. It picks the best of those in range.

Groups are defined in `config.yml`, and you can edit them. An entry can be:

| Entry           | Meaning                                    |
|-----------------|--------------------------------------------|
| `DIAMOND`       | one item                                   |
| `*_ORE`         | name pattern: `*` matches any run of letters (or none) in the item's ID |
| `#minecraft:logs` | vanilla or datapack item tag (`#logs` also works) |
| `@edible`, `@fuel`, `@record` | item properties              |

The same forms work on signs. See [`*` patterns, `#` tags and `@` properties](#-patterns--tags-and--properties).

#### The default groups

A fresh `config.yml` has these 56 groups. Every name fits on one sign line. The
item counts are for Paper 26.3. An item can be in more than one group.

| Sign label | What's in it | Items |
|---|---|---|
| `[music discs]` | Music discs | 23 |
| `[templates]` | Smithing templates | 19 |
| `[rare items]` | Rare & trophies, incl. mob heads and skulls | 25 |
| `[buckets]` | Buckets | 12 |
| `[brewing]` | Potions & brewing | 9 |
| `[books]` | Books & enchanting | 5 |
| `[maps]` | Maps & navigation | 23 |
| `[fireworks]` | Fireworks | 2 |
| `[food]` | Food | 38 |
| `[seeds]` | Seeds | 7 |
| `[farming]` | Crops & farming | 14 |
| `[mob drops]` | Mob drops | 27 |
| `[ores]` | Ores | 22 |
| `[mineral blocks]` | Mineral blocks | 8 |
| `[ingots and gems]` | Ingots & gems | 19 |
| `[materials]` | Crafting materials | 9 |
| `[amethyst]` | Amethyst | 6 |
| `[animal gear]` | Saddles & animal armor, lead, name tag | 31 |
| `[armor]` | Armor | 31 |
| `[weapons]` | Weapons & ammo | 23 |
| `[tools]` | Tools | 34 |
| `[boats]` | Boats | 22 |
| `[rails]` | Rails & minecarts | 9 |
| `[redstone]` | Redstone | 72 |
| `[light sources]` | Light sources | 40 |
| `[storage]` | Storage | 45 |
| `[workstations]` | Workstations: villager job blocks, crafting table, furnace, anvils, enchanting table, jukebox, bell, respawn anchor | 22 |
| `[signs]` | Signs and hanging signs | 26 |
| `[banners]` | Banners & banner patterns | 26 |
| `[pottery]` | Pottery sherds & decorated pot | 24 |
| `[beds]` | Beds | 17 |
| `[decorations]` | Shelves, cushions, copper golem statues, item frames, painting, armor stand, bookshelves, flower pot | 44 |
| `[wool]` | Wool & carpet | 64 |
| `[concrete]` | Concrete: blocks, powder, slabs & stairs | 64 |
| `[terracotta]` | Terracotta: plain, dyed & glazed | 33 |
| `[glass]` | Glass | 35 |
| `[dyes]` | Dyes | 16 |
| `[mushrooms]` | Mushrooms | 6 |
| `[flowers]` | Flowers | 27 |
| `[foliage]` | Saplings & leaves, cherry and flowering azalea leaves, mangrove propagule | 26 |
| `[sculk]` | Sculk | 4 |
| `[sulfur]` | Sulfur & cinnabar | 28 |
| `[ocean]` | Ocean | 45 |
| `[nether]` | Nether | 44 |
| `[end]` | The End, incl. chorus flower | 11 |
| `[copper]` | Copper blocks | 80 |
| `[logs]` | Logs & planks | 64 |
| `[wooden building]` | Wooden building | 80 |
| `[dirt and sand]` | Dirt, sand & gravel | 12 |
| `[ice and snow]` | Ice & snow | 6 |
| `[plants]` | Plants | 26 |
| `[stone]` | Stone | 47 |
| `[deepslate]` | Deepslate | 20 |
| `[tuff]` | Tuff | 14 |
| `[sandstone]` | Sandstone | 20 |
| `[building]` | Bricks, quartz & other building | 29 |

**Renamed in 3.1.0.** Four groups were renamed so their sign labels fit on one line:

- `smithing-templates` became `templates`.
- `crafting-materials` became `materials`.
- `saplings-and-leaves` became `foliage`.
- `concrete-and-terracotta` became two groups, `concrete` and `terracotta`.

Upgrading never changes the `groups:` in your `config.yml`, so a server that started before
3.1.0 keeps the old names. Both names work on signs either way: `[foliage]` finds an older
config's `saplings-and-leaves`, and `[crafting materials]` finds the new `materials`. To get
the new groups themselves, copy them from the [default
`config.yml`](https://github.com/mhardin18/copperGolemClerk/blob/main/src/main/resources/config.yml).

**Changed in 3.2.0.** `utility-blocks` was merged into `workstations`. `decorations` was
split into `signs`, `banners`, `pottery` and `beds`, and mob heads moved to `rare-items`. A
`[utility blocks]` sign finds the new `workstations` on a fresh config. An older config
keeps its own `utility-blocks` and `decorations` groups.

### Smarter use of empty chests

Vanilla golems walk to the nearest chest they haven't tried. They drop their item into the
first empty chest they reach, often before finding the chest where it belongs. After trying
10 chests they pause and start over from the nearest, so far-away chests seldom get used.

With `prefer-matching-chests` (on by default), a golem carrying an item first looks over
every chest in its range. That is `search-distance` blocks sideways (32 by default) and 8 up
or down, in loaded chunks.

- If a chest matches the item and has room, the golem walks straight to it. A match is a
  sign or frame label, the same item, or the same group.
- Empty chests are used only when no matching chest has room.
- If the golem hasn't delivered by the time the walk should have taken (30 seconds or
  more), for example because the chest is walled off, it works through the fallback steps in
  "Items with no home" below.

### Item frame labels

Hang an item frame (or glow item frame) with an item in it on any side of a chest. Golems
then put **only that exact item** in that chest. A label overrides groups and the
empty-chest rule. Several frames on one chest allow each framed item. Empty frames are
ignored. Turn this off with `item-frame-labels: false`.

### Sign labels and overflow chests

Put a wall sign on any side of a chest, a standing sign on top, or a hanging sign just
above. Write rules in square brackets. A line can hold one rule or several (`[ice][snow]`).
A rule too long for one line can carry on to the next
([see below](#labels-too-long-for-one-line)). The chest takes anything any rule matches:

| Line        | Accepts                         |
|-------------|---------------------------------|
| `[logs]`    | the `logs` group from the config |
| `[oak log]` | that one item                    |
| `[*_ORE]`   | items matching the pattern       |
| `[#planks]` | items in that tag                |
| `[Overflow]`| anything with no other home      |
| `[No Golems]`| nothing: golems leave it alone  |
| `[Only Logs]`| on a copper chest: golems take only logs out |
| `[Fuel]` or `[Fuels]` | golems take furnace fuel from it (furnace feeding) |
| `[Furnace Supply]` | golems take items to cook from it (furnace feeding) |

How a label is read:

- Case doesn't matter, and spaces count as underscores.
- A group name wins over an item with the same name. For one exact item that shares a
  group's name, use a framed item instead.
- Group names work singular or plural: `[Rail]` is `rails`, `[ore]` is `ores`,
  `[light source]` is `light-sources`.
- If a word matches no group and no item, it matches items with that word in their name.
  `[carpet]` is every carpet.
- An item that comes in all 16 dye colours also covers them. `[Shulker Box]`, `[Candle]`
  and `[Bundle]` take the plain item and every colour. `[Red Candle]` takes only red.
  (`[Terracotta]` is a group: plain, dyed and glazed terracotta.)

#### Labels too long for one line

A sign line holds about 15 letters, fewer with wide letters like `m` and `w`. The game
measures the width, not the number of letters.

A label that doesn't fit can carry on to the next line. Leave the `[` open at the end of one
line and close it with `]` on a later one. The lines are joined with a space, or with
nothing after a `-` or `_`:

| Sign lines | Read as |
|---|---|
| `[Only light` / `sources]` | `[Only light sources]` |
| `[Only ingots` / `and gems]` | `[Only ingots and gems]` |
| `[*_SMITHING_` / `TEMPLATE]` | `[*_SMITHING_TEMPLATE]` |
| `[ores] [Only` / `redstone]` | `[ores]` and `[Only redstone]` |

Each side of a sign is read on its own, so a label can't carry on to the back. A label that
is never closed is ignored, and `/cgc check` on the chest says so. `/cgc label` and
`/cgc overflow` mark a suggested label that is too wide for one line.

#### `*` patterns, `#` tags and `@` properties

A label that starts with `#` or `@`, or has a `*` in it, is never read as a group name or a
word search. It means exactly what it says.

**`*` matches any run of letters, or none, in the item's ID.** The ID is the name in `/give`,
like `red_sand`. Spaces count as underscores and case doesn't matter. The whole ID has to fit:

| Sign | Matches | Examples |
|---|---|---|
| `[*sand]` | IDs that **end** in `sand` | sand, red sand, soul sand, suspicious sand (not sandstone) |
| `[sand*]` | IDs that **start** with `sand` | sand, sandstone, sandstone slab, stairs and wall |
| `[*sand*]` | IDs that **contain** `sand` | all of the above plus red, cut and chiseled sandstone |
| `[*_ore]` | every ore block | coal ore, deepslate iron ore, nether gold ore... |
| `[raw_*]` | IDs that start with `raw_` | raw iron, gold and copper, and their blocks |
| `[*]` | **every item** | see below |

`[*]` on its own matches everything.

- On a furnace, it means "anything this furnace can cook".
- On a chest, it takes any item. Because it's a label, golems pick it before chests that
  only match by what's in them, and before `[Overflow]` and empty chests. A label that
  covers fewer items, like `[logs]`, still wins.
- For a catch-all that's used only when nothing else fits, use `[Overflow]` instead.

**`#` means a Minecraft item tag.** `[#minecraft:logs]` or just `[#logs]` (`minecraft:` is
assumed) takes every item in that tag, including tags added by datapacks (`[#mypack:gems]`).
The [Minecraft Wiki](https://minecraft.wiki/w/Item_tag_(Java_Edition)) lists the vanilla
tags. An unknown tag, or `[#]` with no name, matches nothing.

**`@` means a property.** `[@edible]` is anything you can eat, `[@fuel]` is anything that
burns in a furnace, and `[@record]` is music discs.

A pattern, tag or property counts as specific as the number of items it matches. `[*_ore]`
(18 items) beats `[@fuel]` (every log, plank and wooden item, and more). If a line matches
no item at all, golems ignore it, and `/cgc check` on the chest says it "matches no group or
item, so it's ignored". The same three forms work in the `groups:` lists in `config.yml`
and after `[Only ...]`.

#### `[No Golems]` and `[Only ...]`

A `[No Golems]` sign (any case, `[no golem]` works too) keeps golems away from a chest
completely. They never put anything into it, and on a copper chest they never take anything
out. Other labels on the same chest are ignored. Use it for a copper chest you want as plain
storage.

An `[Only ...]` sign on a copper chest is a filter. Golems take only those items out of it
and leave the rest. After `Only` comes any label from the table above, so `[Only Logs]`,
`[Only Oak Log]`, `[Only *_ORE]` and `[Only #planks]` all work. Several `[Only ...]` lines
add up. It suits a shared input chest where you keep a few things you don't want sorted.
`/cgc check` on the copper chest shows the filter and how many items it's leaving behind. On
a chest that isn't a copper chest it does nothing.

#### Which chest wins

When several chests would take an item, golems use the most specific one. Labels (frames and
signs) come before contents. The order is:

1. A framed item.
2. The sign label covering the fewest items.
3. A chest already holding that exact item.
4. A group match by contents.

So a stew goes to `[stews]` before `[food]`, and a `[logs]` chest wins over an unlabelled
chest that happens to hold birch logs. Frame and sign labels on one chest combine.

Where a golem puts an item, in order:

1. A matching chest with room (label, same item or group).
2. An `[Overflow]` chest.
3. An empty chest.

`overflow-before-empty: false` swaps the last two. Overflow chests never count as a match
for what's already in them.

#### Chests golems never use

Golems never use a chest that vanilla golems can't:

- one with a solid block or a sitting cat on top
- a locked one
- a waterlogged one (vanilla golems walk up to it and wait there for good)

They also skip chests that drain through hoppers into a copper chest, so items don't go
round in circles. `/cgc check` on a chest tells you if any of these applies.

### Items with no home

If a golem can't deliver its item, it works through four steps:

1. Steer to the most specific matching chest, overflow chest or empty chest.
2. Try any matching chest (the most specific one may be unreachable).
3. Try only overflow or empty chests.
4. Wander like a vanilla golem for 30 seconds.

Each of the first three lasts as long as walking to the farthest chest it tries takes. That
is 30 seconds or more, and longer with a bigger `search-distance`. A step with nothing new
to try is skipped. An item with no home in range goes straight to the vanilla step.

After that, with `return-unsortable-items` (on by default), the item is put back into the
copper chest it came from. Golems can't walk to copper chests while carrying, so the plugin
moves it directly. The item is marked "no home" there for `homeless-retry-minutes` (default 5).
Golems take other items first and skip a copper chest that has only "no home" items left.

To give the item a home, add a sign or item frame label. Golems within `search-distance` of
it try again right away. `/cgc reload` clears every mark.

Steps count only time the golem is likely active: walking, or a player within the server's
entity activation range. So golems that Paper pauses far from players don't run out of time.
A golem waiting its turn next to a chest that takes its item isn't sent back, unless it is
still waiting a minute after the last step. That means it can't use that chest.

### Re-sorting the overflow chest

With `fetch-from-overflow` (on by default), a golem with nothing to do walks to an
`[Overflow]` chest. "Nothing to do" means an empty hand and nothing to take from any copper
chest in range. It takes up to `carry-amount` (16) of an item that now has a home, then
sorts it. So when you add a chest for something that was piling up in overflow, golems move
it over on their own.

- Up to `fetchers-per-overflow-chest` golems (default 2) fetch from one overflow chest at a
  time, each a different item, so they don't crowd it.
- Items with no home are left in overflow.
- Items in an overflow chest golems can't open (locked, blocked or waterlogged) are left too.
- If an item loses its home while the golem is on its way (that chest fills up or is
  removed), the golem stops without taking it.
- Golems never walk to an empty copper chest.

When a golem takes from or puts into a chest, barrel or shulker box, its lid opens for a
moment, as with vanilla golems. This also happens when it fetches from `[Overflow]`, uses a
`[Fuel]` or `[Furnace Supply]` container, or puts an item back in its copper chest.

**Overflow chests that a hopper fills.** A hopper above the chest facing down, or beside it
facing in, fills it a few items at a time. The output chest of a smelter is an example. Golems
don't walk over for every ingot. They fetch an item from such a chest once 8 of it have built
up (`overflow-fetch-min`), or once it has waited 2 minutes (`overflow-fetch-wait-seconds`), so
the last few still get sorted. Overflow chests that no hopper feeds are emptied right away.
`/cgc overflow` mentions when a hopper fills the chest.

### Bigger loads

Vanilla golems carry 16 items per trip. `carry-amount` (1 to 64, default 16) changes that.
Right after a golem takes items out of a copper chest, it takes more of the same item from
that chest, or puts the extra back if the setting is under 16.

- An item that stacks to less (16 ender pearls, one tool) is carried that many at most.
- Items held back by `[Only ...]` or returned as homeless aren't added.
- At 64, a big sorting job needs a quarter of the trips.

### Golems run your furnaces

Put a label on a furnace, smoker or blast furnace, the same way as on a chest (a sign or an
item frame). The label says what to cook there, such as `[raw iron]`, `[sand]` or `[ores]`.
Golems then keep it going: they bring items to cook and fuel, and take out what's done.
Furnaces without a label are never touched.

**Where items to cook come from.** Only from containers with a `[Furnace Supply]` sign. Put
the raw iron, sand or logs you want cooked there. A copper chest with a `[Furnace Supply]`
sign works too. What golems carry out of any other copper chest is sorted as usual and never
goes into a furnace.

- Idle golems fetch from the supply and split each load between up to 2 labelled furnaces
  that take it, at least 8 items each (`furnace-split-min`). When more furnaces are running
  low, the emptiest get it first.
- A load never goes to more than 2 furnaces. The furnaces of a load in transit are left to
  that golem, because a big load spread over many furnaces kept other golems waiting.
- A furnace is topped up once it's running low (`furnace-refill-below`, 16 items). Golems
  take only as much as the furnaces have room for.
- A golem never brings a load to a furnace another golem is already bringing one to.
- A golem whose furnace filled up before it got there waits for room (`furnace-wait-seconds`,
  20). Then it puts anything left back in the supply chest. Nothing taken from a supply is
  ever sorted elsewhere.
- Label a supply chest `[Furnace Supply] [raw iron]` to have golems also sort raw iron into it.

**What goes in.** Only items the label covers *and* that kind of furnace can cook. `[raw
iron]` on a blast furnace takes raw iron. `[food]` on a smoker takes only the raw food a
smoker can cook. Group labels can cover more than you expect (`[ores]` also covers ore blocks
you mined with Silk Touch). `/cgc check` on the furnace lists exactly which items golems will
put in, and points out label entries that add nothing.

**What comes out.** An idle golem takes out what the furnace has made and sorts it like
anything else. The exception is a furnace with a hopper (or hopper minecart) under it that
collects the output.

- Golems wait until 8 items are ready (`furnace-take-out-at`) so a trip is worth it.
- They go sooner if the furnace has stopped. That means nothing left to cook, no fuel, a
  full output slot, or an output slot holding something else (an item a player left there).
  That last case keeps the furnace from lighting until the item is taken out.
- If no chest in range would take the items, and there's no copper chest to return them to,
  they stay in the furnace rather than in a golem's hands.
- Items taken out of a furnace are never put back into one. So `[stone]` can't turn into an
  endless cobblestone, stone, smooth stone loop.

**What golems do first.** An idle golem keeps furnaces going before it sorts. In order:

1. Furnace upkeep: empty a furnace that has stopped (full output slot), take out empty
   buckets and refill them at a lava cauldron, bring fuel, bring items to cook to furnaces
   running low.
2. Sort copper chests.
3. Take out finished items (`furnace-take-out-at`).
4. Re-sort `[Overflow]` chests.
5. Say hello to iron and snow golems.

A big bank of furnaces can't keep copper chests waiting for ever. A golem that has done 3
furnace jobs in a row while a copper chest has items sorts once before more furnace work
(`furnace-jobs-before-sorting`). 0 puts sorting first, as before 3.1.0.

**Fuel.** Golems only take fuel from containers with a `[Fuel]` sign, so the coal in your
other chests is never burned.

- They use the first fuel in `furnace-fuel` that a `[Fuel]` container in range holds
  (`[charcoal, coal, lava_bucket]` by default).
- They fetch fuel only when the fuel in the furnace won't cook what's waiting. They then
  bring enough for that plus 16 more items (`furnace-fuel-ahead`), so the next load doesn't
  need its own fuel trip. For example, 8 raw iron waiting get 3 charcoal.
- Only one golem at a time brings fuel to a furnace. Fuel a golem couldn't put in goes back
  to a `[Fuel]` container.
- A furnace still burning a fuel lower on the list isn't topped up with it. It switches once
  that runs out.
- A fuel someone else put in (not in `furnace-fuel`) is left alone.
- A `[Fuel]` chest with no other label never receives sorted items. Label it
  `[Fuel] [charcoal]` to have golems fill it with the charcoal your furnaces make.

**Lava.** A lava bucket lasts 100 items, and a lit furnace burns its fuel out even with
nothing left to cook. So golems use lava only when both of these are true:

- The furnace has a backlog of 100 or more items (`furnace-lava-min-items`). The backlog is
  what's waiting in it, what other golems are bringing, and its share of the matching items
  in `[Furnace Supply]` containers in range.
- A `[Fuel]` container also has another fuel.

For a smaller backlog, golems bring just enough charcoal or coal instead. `/cgc check` on
the furnace shows its backlog.

Lava buckets leave an empty bucket in the furnace. Golems take it out and refill it at a
lava cauldron in range (never from lava in the world), then put it back in a `[Fuel]`
container.

A cauldron that dripping lava keeps refilling is worth waiting for. That means pointed
dripstone hanging over it, within 11 blocks, with a lava source on top of the block the
dripstone hangs from. Until it's full, golems put the empty buckets in a `[Fuel]` container.
An idle golem takes one to the cauldron as soon as it fills and brings the lava bucket back.
With no such cauldron in range and none full, the empty bucket is sorted like anything else.

**Hand-built smelters.** A chest sitting on a hopper that leads into a furnace (directly or
through more hoppers) is never used for sorting. Golems don't fill your smelter's input or
fuel chest with random items. Golems don't feed hoppers, so label the furnaces themselves if
you want golems to run them.

**Claims.** Golems only use furnaces and `[Fuel]` and `[Furnace Supply]` containers in the
claim their copper chest is in. They also use those in a claim where that claim's owner has
container trust. `furnace-trusted-claims: false` turns that off.

`[No Golems]` and locked furnaces and chests are left alone. Turn it all off with
`furnace-feeding: false`. Furnaces, `[Fuel]` and `[Furnace Supply]` signs and smelter chests
are then treated as if the feature didn't exist.

### Golems give way to each other

Golems in a crowded room or a narrow aisle can end up nose to nose, pushing against each
other. With `golems-give-way` (on by default), a golem that is stuck because another golem is
in its way steps one block aside and waits for the other to pass. Stuck means trying to walk
but barely moving for a second.

- A golem with empty hands gives way to one carrying an item.
- If both carry something, or both are empty, the same golem of the pair always gives way,
  so they never both dodge.
- A golem busy at a chest never steps aside. Golems that are just passing each other are
  left alone.
- A golem never steps aside into fire, lava, water, a bubble column, a cobweb, powder snow,
  a berry bush, a wither rose, pointed dripstone, a cactus or a trapdoor. It never steps onto
  a campfire, magma block or trapdoor. It backs up to a safe spot instead.

### Golems keep off trapdoors

Vanilla golems treat an open trapdoor as floor. A trapdoor over a hole (a ladder shaft, a
drop into a farm) catches them: they walk onto it and fall in. With `avoid-trapdoors` (on by
default), golems plan their paths around trapdoors, open or closed, and never stand on one.
A golem whose only way to a chest is across a trapdoor won't reach it, and its item goes
elsewhere as usual.

### Golems stay out of portals

A golem that wanders into a Nether portal ends up in the Nether, far from its copper chest
and unable to get back. With `avoid-portals` (on by default), golems never go through a
Nether portal, End portal or End gateway. One that steps into a portal just stays in its own
world. A golem on a lead or riding a boat or minecart goes through as usual, so you can
still take golems to another dimension.

### Bigger storage areas

Vanilla golems look for chests up to 32 blocks away in each direction sideways, and 8 up or
down. `search-distance` raises the sideways distance, up to 128 blocks. It can't go below
vanilla's 32. Golems then find chests, copper chests, `[Overflow]` chests, furnaces and lava
cauldrons further away.

Vanilla golems never see chests past 32 blocks. For those, the golem walks there and the
plugin moves the items, as it does for chests above vanilla's reach.

When the plugin walks a golem somewhere (a furnace, a supply, a far or high chest, an
overflow chest), it gives up once the golem has gone 10 seconds without getting a block
closer. A stuck golem moves on quickly, and a slow one (Slowness, soul sand, a crowd) gets
there. No walk lasts longer than twice the time for the whole `search-distance`: 81 seconds
at 32, 132 at 64, 235 at 128. Time frozen in a chunk the server isn't ticking doesn't count.

A bigger area costs more. Each search covers about 2.5 times as many chunks at 64 as at 32,
and about 7.4 times at 128. Golems also spend longer walking than sorting. Chests in unloaded
chunks are never found, so a distance beyond your server's view distance (10 chunks, or 160
blocks, by default) gains nothing unless players keep that ground loaded.

**Impact on tick rate.** Larger searches take more server time on every tick. This was
measured on a test server with 20 golems working among 1,817 chests. The server alone used
1.30 ms per tick.

| `search-distance` | Server ms per tick | Added per working golem |
|---|---|---|
| 32 (vanilla) | 2.40 | about 0.055 ms |
| 64 | 3.15 | about 0.09 ms |
| 96 | 4.38 | about 0.15 ms |
| 128 | 6.45 | about 0.26 ms |

The server has 50 ms per tick (20 TPS) for everything, so the cost grows with the number of
golems working at once. 100 busy golems would use about 5.5 ms at 32, but about 26 ms (half
the tick) at 128. Raise the setting only as far as your storage needs. Watch `/mspt` (or
`/tps`) after changing it. If the server falls behind, lower it or reduce the number of
golems (see [Golem limits](#golem-limits)).

### Taller stacks of chests

Vanilla golems only open chests up to 2 rows above the floor they stand on: the chest at
their feet and the one on top of it. Two settings raise that, up to 6 rows:

- `deliver-reach` (default 2): how high golems put items into chests (labelled, matching,
  `[Overflow]` and empty chests).
- `take-reach` (default 2): how high golems take from copper chests, and from `[Overflow]`
  chests when fetching.

For a chest above row 2, the golem walks to the floor in front of the stack and the plugin
opens the chest and moves the items, as the golem would.

- The golem needs floor to stand on beside the stack. Standing on top of other chests doesn't
  count.
- Only floor a golem can walk to counts. The top of the ground behind a wall of chests built
  into a hillside doesn't make the top row count as low.
- A chest within vanilla's reach is still preferred over an equally good one higher up.

`/cgc check` on a high chest says how many rows up it is and whether golems reach it. It also
says when a chest is level with a spot golems can't walk to (asking a golem nearby), and how
high it is from the floor they do use.

### Barrels and shulker boxes

Vanilla golems only put items into chests and trapped chests. List more containers in
`destination-containers` and golems use those as well:

```yaml
destination-containers: [chest, trapped_chest, barrel, shulker_box]
```

`shulker_box` covers every colour. Barrels and shulker boxes work like chests: sign and frame
labels, matching by contents, `[Overflow]` and `[No Golems]` all apply.

Vanilla golems can't open them, so the golem walks up to one and the plugin puts the item in
(as for chests above vanilla's reach). A shulker box needs open space in front of its lid,
and never takes a shulker box. Leave `chest` out of the list to keep golems away from plain
chests.

An empty barrel or shulker box counts as an empty chest. With `allow-empty-chests` on, items
with no other home can end up in one. Put a `[No Golems]` sign on barrels you use for
something else.

### Switching a golem back to vanilla

Right-click a copper golem with bamboo (`golem-toggle-item`) to switch that golem to plain
vanilla behavior. Right-click again to switch it back. The mode is saved on the golem.

A vanilla golem gets none of the plugin's features: no smart sorting or labels, no overflow,
returns, fetching, trapdoor avoidance, taller reach, giving way or tidying.

While you hold bamboo, golems using this plugin show a small green light on their antenna.
Vanilla golems don't. Nobody else sees the lights.

Every copper golem within 48 blocks also shows a small label saying what it's doing:
`carrying 12 birch log`, `fetching granite`, `no chest for 3 kelp`, `taking 5 kelp back`,
`giving way`, `saying hello`, `lost, walking home`, `vanilla mode`,
`over the golem limit: vanilla` or `idle`. Once its copper starts to weather, a second line
says how far along it is (`oxidized: may turn into a statue` in red). Nobody else sees the
labels. Turn them off with `status-labels: false`.

Anyone with `coppergolemclerk.toggle` (everyone, by default) can switch golems. Where
GriefPrevention is installed, only players allowed to build where the golem stands can.
Set `golem-toggle-item: none` to turn switching off, and `mode-markers: false` to hide the
lights.

The plugin only ever changes copper golems. Iron and snow golems are never affected.

### Golems say hello

A copper golem with nothing to do (empty hands, nothing to fetch, no copper chest in range
with anything to sort) walks over to an iron or snow golem within 16 blocks and says hello.
They face each other, it waves, and hearts float up. Then it goes back to its routine and
doesn't greet again for `greet-cooldown-minutes` (default 5).

It never follows. If it can't get there in 20 seconds it gives up, and any work turning up
ends the visit at once. Turn it off with `greet-other-golems: false`.

### Lost golems find their way home

Each golem remembers the copper chest it last took items from. It counts as far from home if
it ends up more than 40 blocks from its own copper chest, or 12 up or down (say after
falling down a shaft), and from any other copper chest too. Then it walks back.

Its owner gets an alert saying where it is if either of these happens:

- It gets no closer for 20 seconds.
- It's stuck where it can't walk to a copper chest (in a pit, walled in).

"Full-chest alerts" below explains who the owner is. An alert looks like this:

> [Golem Clerk] A copper golem is lost at -757, -55, 102, 118 blocks from its copper chest at -760, 63, 98, and can't find a way back.

The golem tries again every 5 minutes. A golem moved somewhere with its own copper chest (on
a lead, say) adopts that chest as its new home. Golems on a lead are left alone. `/cgc golems`
shows which golems are lost. Turn this off with `rescue-lost-golems: false`.

Teleporting stuck golems home is possible but off by default. It needs both of these:

- `lost-golem-teleport-home: true`
- The `coppergolemclerk.teleport` permission for the owner of the claim the golem's copper
  chest is in. Nobody has it until you grant it, for example with LuckPerms.

Golems whose chest isn't in a claim are never teleported.

### Full-chest alerts

The plugin sends an alert when a golem can't put an item in the chest labelled for it
because the chest is full. It also does so when an `[Overflow]` chest is full or nearly full
(`overflow-alert-percent`, default 90% of its slots).

> [Golem Clerk] The [logs] chest at 120, 64, -40 is full: golems can't put birch log in it.

Who hears it:

- **In a GriefPrevention claim:** the claim's owner, and nobody else. An owner who's
  offline is told when they next join.
- **Outside every claim** (or on a server without GriefPrevention): there's no owner to
  tell, so it goes to the players online with `coppergolemclerk.alerts` (ops by default),
  typically the server's staff.

Every alert is also logged. The same chest isn't mentioned again for `alert-cooldown-minutes`
(default 30). Turn alerts off with `full-chest-alerts: false`.

### Golems tidy the chests they use

With `tidy-chests` (on by default), a golem tidies a chest while it has the lid open to put
something in, and again as the item drops in. The new item then sits with its kind:

- Partial stacks of the same item are merged. Two stacks of 20 and 10 oak logs become one
  stack of 30. Items that differ, such as a renamed or enchanted copy, stay separate.
- Like items are moved next to each other: by group, in the order the groups are listed in
  `config.yml`, then by item. Stacks fill the chest from the first slot.

Copper chests are left alone. So is a chest a player has open, which is tidied after they
close it.

### Golem limits

Two settings cap how many copper golems in one GriefPrevention claim use this plugin. Both
are 0 (off) by default.

- `max-golems-per-claim`: how many golems every claim may have. Each claim has its own
  limit, even when one player owns several. Admin claims too. A subdivision counts with its
  claim.
- `claim-blocks-per-extra-golem`: bigger claims get more. A claim gets one more golem for
  every this many claim blocks of its area, on top of `max-golems-per-claim`.

For example, with `max-golems-per-claim: 5` and `claim-blocks-per-extra-golem: 2500`:

| Claim | Claim blocks | Golems that use the plugin |
|---|---|---|
| 9 × 9 | 81 | 5 |
| 50 × 50 | 2,500 | 6 |
| 100 × 100 | 10,000 | 9 |
| 300 × 300 | 90,000 | 41 |

With `max-golems-per-claim: 0`, a claim smaller than `claim-blocks-per-extra-golem` gets no
golems with the plugin at all.

How the limit is applied:

- Golems are recounted every few seconds.
- The oldest keep the plugin. The newest ones over the limit sort like vanilla, and the
  claim's owner is told. For an admin claim, players with `coppergolemclerk.alerts` are told.
- Golems a player switched to vanilla don't count.
- Only golems in loaded chunks are counted. In a claim bigger than players keep loaded,
  which golems use the plugin can change as parts of it load.
- Golems on unclaimed land aren't limited. Neither is any golem on a server without
  GriefPrevention.
- `max-golems-outside-claims`, which shared one limit among all the unclaimed land in a
  world, was removed in 3.1.0.

**Tick cost.** A working copper golem costs about 0.1 ms per tick, whether it uses this
plugin or sorts like vanilla. The cost is the golem's own AI and pathfinding. The plugin adds
nothing measurable on top. This was measured on Paper 26.3 with 0 to 200 golems all sorting
at once:

| Golems | With the plugin | Vanilla |
|---|---|---|
| 20 | 3.9 ms | 3.5 ms |
| 50 | 5.3 ms | 6.4 ms |
| 100 | 12.2 ms | 10.5 ms |
| 150 | 17.0 ms | 17.9 ms |
| 200 | 21.4 ms | 19.4 ms |

With no golems the server used 1.7 ms, and it starts to lag at 50 ms per tick. Your numbers
depend on your hardware.

Golems over the limit keep sorting like vanilla. At the default `search-distance`, the limits
therefore don't lower tick time. They cap how many golems get this plugin's sorting. With
`search-distance` raised, golems over the limit search only vanilla's 32 blocks, which saves
the extra cost of the longer searches (see [Bigger storage areas](#bigger-storage-areas)). To
limit the tick cost of the golems themselves, limit the number of golems.

Every setting is listed in [Settings reference](#settings-reference) below.

## Update notices

Once a week, and a minute after the server starts, the plugin asks GitHub for its list of
releases. If there's a newer version built for your server's Minecraft version, it's written
to the server log. Players with `coppergolemclerk.admin` are told when they join (once per
release), with a link to the release page. A release that needs a newer Minecraft version is
mentioned in the log only.

Nothing is downloaded or installed, and nothing is sent besides the request for the release
list. `/cgc version` checks right away. Turn it off with `update-check: false`.

## Commands

The alias is `/cgc`.

Players can use `help`, `find`, `check`, `label`, `overflow`, `storage` and `stats`. They can
use `golems` too if `golems-command-for-players` is on. Players only ever see their own
claims or containers they could open. See [Permissions](#permissions).

Everything else, and the forms with a position or `all`, is for admins
(`coppergolemclerk.admin`, op by default).

- `/cgc help`: list the commands. `/cgc help <command>` explains one.
- `/cgc reload`: reload `config.yml`.
- `/cgc version`: the installed version, and whether a newer release is out (checks now).

### `/cgc check`

Looking at a chest, it shows:

- What each label on it means (`[Horse]` = items with "horse" in their name, 9 items: ...).
- Labels that match nothing.
- What the chest takes, and whether it takes the item in your hand.
- Anything stopping golems from using it: a block on top, a lock, a hopper feeding a copper
  chest, or a full chest.
- Sign-label advice, as `/cgc label` gives (below).

The label advice depends on the chest:

- Unlabelled chest: labels for what's in it, most specific first, and which items wouldn't fit.
- Labelled chest: anything inside that its label doesn't cover, and a label that would.
- Empty chest: labels for the item in your hand.
- `[Fuel]` and `[Furnace Supply]` chests and copper chests get no label advice.
- An `[Overflow]` chest points to `/cgc overflow`.

Players can only check containers they could open.

Not looking at a chest, it shows which groups the item in your hand belongs to.

### `/cgc label`

Looking at a chest, it says what to write on its sign, from what's in it:

- One kind of item: its own label, then its groups.
- Several kinds: the most specific group they all share.
- No shared group: the group most of them fit, and which items don't.

On a labelled chest it says whether everything in it matches. It only suggests. You place the
sign. Players only get suggestions for containers they could open.

### `/cgc overflow`

Shows what's in the `[Overflow]` chests within golem range of you (`search-distance` blocks
sideways, 8 up or down), split into:

- items with no chest (with a sign label to use for each group)
- items whose chest is full
- items golems will move on their own

Players see only the `[Overflow]` chests they could open.

### `/cgc storage`

Shows how full the storage around you is. Containers within golem range (`search-distance`
blocks sideways, 8 up or down) are grouped by label, or by what they hold if unlabelled,
fullest first. Full and nearly full (90%) ones are flagged. After that come the `[Overflow]`
chests, how many empty chests are left for new kinds of items, and how many items are waiting
in copper chests. Players only see containers they could open.

### `/cgc golems`

Lists the 10 copper golems nearest you. For each it shows its position (admins can click it
to teleport there), distance and direction, what it carries, its copper and its mode.

- **Players** (only with `golems-command-for-players: true`) see just their own golems: in
  claims they own, or whose copper chest is in one. Without GriefPrevention, that means the
  golems within 48 blocks of them.
- `/cgc golems <radius>` lists only the golems within that many blocks of you. The header says
  "within N blocks". `find-max-radius` doesn't apply, since nothing is searched.
- `/cgc golems all` lists every golem in every world.
- Golems in unloaded chunks are listed where they were last seen. The plugin keeps every
  golem's last spot in `golems.yml` and forgets golems not seen for 30 days.
- A radius counts along each axis (sideways and up/down), as `/cgc find` does, not in a
  straight line.

### `/cgc stats`

Shows how many items golems have put into chests in your claims today, in the last 7 days and
all time. Numbers are kept in `stats.yml`.

- Admins (`coppergolemclerk.allclaims`) also see the whole server's numbers, the
  most-sorted items and the busiest claims.
- Without GriefPrevention there are no claims, so everyone sees the server's numbers.
- With `join-summary` (on by default), players are told when they join what golems sorted
  in their claims while they were away and how many of their chests filled up.

### `/cgc find`

`/cgc find <item>` shows which containers near you hold an item, nearest first, with how many
in each. Items inside shulker boxes count too. The nearest `find-highlight-count` (5) glow
for you for `find-highlight-seconds` (10). Players can search once every 2 seconds.

**What you can search for.** Anything a sign label takes: an item (`birch log`), a group
(`logs`), a pattern (`*_log`) or a tag (`#minecraft:logs`).

- A word that names one item finds just that item. `torch` is the torch, not copper or soul
  torches. Under the results, an "Also nearby" line lists other items near you with the word
  in their name, and suggests `*torch*` to find them all.
- A number that ends an item's name is part of it (`music disc 13`, `disc fragment 5`).
  `/cgc find music disc 13` is the disc, and `/cgc find music disc 13 50` is that disc within
  50 blocks.

**How far it looks.** `find-radius` blocks (32) around you, sideways and up or down.
`/cgc find <item> <radius>` (`/cgc find birch log 50`) looks that far instead, up to
`find-max-radius` (64) for players. Ops and the console aren't limited.

It only searches chunks that are already loaded, and says how many in range weren't. So a
"No birch log" far from any player isn't certain.

**Where it looks.**

- **By default it only looks in GriefPrevention claims you own**, and never in locked
  containers. Nothing hints at anything else.
- `find-outside-own-claims: true` also lets players find things in unclaimed containers and
  in claims where they have container trust. Other players' claims stay hidden either way.
- Without GriefPrevention there are no claims, so players find nothing unless that setting
  is on.
- Ops see everything (`coppergolemclerk.allclaims`).
- Turn the command off with `find-enabled: false`.

### From the console

Give a position:

- `/cgc check <x> <y> <z> [world]`
- `/cgc overflow <x> <y> <z> [world]`
- `/cgc golems <x> <y> <z> [world] [radius]` lists the 10 golems nearest that spot, within
  the radius if one is given. Without a position, the console lists every golem.
- `/cgc find <item> <x> <y> <z> [world] [radius]`

## Settings reference

All settings live in `plugins/CopperGolemClerk/config.yml`. After editing it, run
`/cgc reload`. No restart is needed. A setting missing from the file uses its default, so an
older `config.yml` keeps working after an update. A mistake in the file is reported with its
line number, and the old settings are kept.

### Sorting

| Setting | Default | What it does |
|---|---|---|
| `match-mode` | `all` | When a chest counts as a group match by its contents. `all`: every item in the chest must share a group with the carried item, so one stray item stops group matching and chests stay tidy. `any`: one item from the group is enough. |
| `prefer-matching-chests` | `true` | A golem first looks over every chest in range (`search-distance` blocks sideways, 8 up or down) and walks straight to the best one that has room. Without it, it tries the nearest chests one by one and drops its item in the first empty one. |
| `return-unsortable-items` | `true` | After the last step, put the item back into the copper chest it came from (or the nearest one with room), instead of carrying it around for ever. |
| `homeless-retry-minutes` | `5` | Items put back are marked "no home" in that copper chest for this long. Golems take other items first and skip a chest with only those left. A new or changed sign or item frame label nearby clears the marks within `search-distance`. `/cgc reload` clears them all. |
| `allow-empty-chests` | `true` | Golems may use an empty chest as a last resort. `false`: only labelled chests, chests already holding the item or its group, and `[Overflow]` chests. |
| `overflow-before-empty` | `true` | When no matching chest has room, try `[Overflow]` chests before empty ones. `false` fills empty chests first. |
| `item-frame-labels` | `true` | An item in a frame on a chest means "only this item goes here". |
| `sign-labels` | `true` | Sign labels (`[logs]`, `[Overflow]`, `[No Golems]`, `[Only ...]`). |
| `tidy-chests` | `true` | Merge partial stacks and keep like items together in the chests golems use. Copper chests and chests a player has open are left alone. |
| `destination-containers` | `[chest, trapped_chest]` | What golems put items into. Add `barrel` and/or `shulker_box` (every colour). Leave `chest` out to keep golems away from plain chests. Golems always take from copper chests. |
| `groups` | 56 groups | The item groups (see [How it works](#how-it-works)). Order matters for tidying: like items are ordered by the first group they're in. |

### Busier golems

| Setting | Default | What it does |
|---|---|---|
| `fetch-from-overflow` | `true` | A golem with nothing to do takes items that now have a home out of an `[Overflow]` chest and sorts them. |
| `fetchers-per-overflow-chest` | `2` | How many golems may fetch from one overflow chest at a time (each a different item). |
| `overflow-fetch-min` | `8` | From an `[Overflow]` chest a hopper fills, golems fetch an item only once this many are there (1 to 64). 1 = a trip for every item. carry-amount = only full trips. Chests no hopper feeds are emptied right away. |
| `overflow-fetch-wait-seconds` | `120` | From an `[Overflow]` chest a hopper fills, golems also fetch an item once it has waited this long (0 to 3600), even with fewer than `overflow-fetch-min`, so the last few still get sorted. 0 = no waiting. |
| `carry-amount` | `16` | Items a golem carries per trip, 1 to 64 (vanilla: 16). Items that stack to less (ender pearls 16, tools 1) are carried that many at most. |
| `furnace-feeding` | `true` | Golems feed, empty and fuel furnaces, smokers and blast furnaces that have a label, with items from `[Furnace Supply]` containers. Unlabelled furnaces are never touched. `false` turns off everything furnace-related: furnaces are left alone, `[Fuel]` and `[Furnace Supply]` signs mean nothing, and chests on a smelter's hopper are ordinary chests again. |
| `furnace-fuel` | `[charcoal, coal, lava_bucket]` | Fuels golems may put in, most preferred first. Taken only from containers with a `[Fuel]` sign. Unknown names and items that don't burn are reported and skipped. |
| `furnace-lava-min-items` | `100` | Lava buckets go in only when the furnace's backlog (waiting + on the way + its share of the matching `[Furnace Supply]` items) is at least this many items (0 to 1000), and a `[Fuel]` container also has another fuel. A bucket lasts 100 items and burns out even with nothing to cook. 0 = lava whenever it's its turn in `furnace-fuel`. |
| `furnace-fuel-ahead` | `16` | Fuel golems bring covers what's waiting plus this many more items (0 to 64), so the next load doesn't need a fuel trip of its own. 0 = just enough for what's waiting (a fuel trip after nearly every load). Higher = fewer trips, more fuel sitting in fuel slots. |
| `furnace-refill-below` | `16` | A furnace gets more to cook only once this many items or fewer are left in it (0 to 64). Lower (0 = only when empty) can leave furnaces idle while golems are busy. 64 = top up after every item (many wasted trips). |
| `furnace-split-min` | `8` | A load is split between 2 furnaces (never more) only if each gets at least this many items (1 to 64). The emptiest furnaces go first. Above half of `carry-amount` = whole loads to one furnace. |
| `furnace-take-out-at` | `8` | Golems take finished items out once this many are ready (1 to 64), or sooner when the furnace has stopped. 1 = a trip for every item. Higher = fewer trips, items wait longer. Furnaces with a hopper or hopper minecart under them are never emptied by golems. |
| `furnace-jobs-before-sorting` | `3` | Furnace upkeep comes before sorting. After this many furnace jobs in a row while a copper chest has items (0 to 64), a golem sorts once. 0 = sorting always first (the order before 3.1.0). Higher = busier furnaces, copper chests wait longer. |
| `furnace-wait-seconds` | `20` | How long a golem waits at a furnace that filled up before it arrived (0 to 300), before taking the rest back to the `[Furnace Supply]`. 0 = straight back (can loop). Long waits keep that golem idle. |
| `furnace-trusted-claims` | `true` | Golems also use furnaces and containers in claims where the owner of their copper chest's claim has container trust. |
| `deliver-reach` | `2` | How many chest rows up golems put items in, 2 to 6. Above row 2 the golem walks to the foot of the stack and the plugin moves the items. |
| `take-reach` | `2` | How many rows up golems take from copper chests and fetch from `[Overflow]` chests, 2 to 6. |
| `search-distance` | `32` | How far golems look for chests, copper chests, `[Overflow]` chests, furnaces and lava cauldrons, in blocks each way sideways (a square; up and down stays 8). 32 (vanilla) to 128. Above 32 the plugin walks golems to chests vanilla can't see. Larger values cost tick time: about 0.055 ms per working golem at 32, 0.26 ms at 128 (see Bigger storage areas). |

### Looking after golems

| Setting | Default | What it does |
|---|---|---|
| `golems-give-way` | `true` | A golem stuck nose to nose with another steps aside (backing up in a one-wide corridor). Empty hands give way to full ones. |
| `avoid-trapdoors` | `true` | Golems never walk onto or through trapdoors (vanilla golems fall through open ones). |
| `avoid-portals` | `true` | Golems never go through Nether portals, End portals or End gateways, unless on a lead or riding something. |
| `rescue-lost-golems` | `true` | A golem far from any copper chest (40 blocks from its own, or 12 up or down) walks back. One that can't, or is stuck where it can't reach a copper chest, triggers an alert. |
| `lost-golem-teleport-home` | `false` | Off by default. When on, a stuck golem is teleported back to its copper chest, but only if the owner of the claim that chest is in has `coppergolemclerk.teleport` (nobody does unless you grant it; checked through LuckPerms when the owner is offline). Golems whose chest isn't in a claim are never teleported. |
| `greet-other-golems` | `true` | An idle copper golem walks over to say hello to an iron or snow golem within 16 blocks. |
| `greet-cooldown-minutes` | `5` | How long before a golem greets again. |
| `max-golems-per-claim` | `0` | How many copper golems in one GriefPrevention claim use this plugin (0: no limit, unless `claim-blocks-per-extra-golem` is set). Golems on unclaimed land aren't limited. Each claim (admin claims too) has its own limit. The oldest keep it, the newest over the limit sort like vanilla, and the claim owner is told. Vanilla golems cost the same tick time (see [Golem limits](#golem-limits)). |
| `claim-blocks-per-extra-golem` | `0` | Each claim gets one more golem than `max-golems-per-claim` for every this many claim blocks of its area (0: off). |
| `log-golem-deaths` | `true` | Log every copper golem death (cause, place, what it held) and every other removal (statue, `/kill`). |

### Players, alerts and tools

| Setting | Default | What it does |
|---|---|---|
| `golem-toggle-item` | `bamboo` | Right-clicking a golem with this item switches it between this plugin and plain vanilla. `none` turns switching off. |
| `mode-markers` | `true` | While a player holds the toggle item, golems using the plugin show them a small green light. |
| `status-labels` | `true` | While a player holds the toggle item, golems within 48 blocks show them what they're doing and how weathered they are. |
| `full-chest-alerts` | `true` | Tell the claim owner (or players with `coppergolemclerk.alerts`) when a labelled chest is full or an `[Overflow]` chest fills up. Offline owners are told when they join. |
| `overflow-alert-percent` | `90` | Warn when an `[Overflow]` chest has this share of its slots in use. `0`: only when completely full. |
| `alert-cooldown-minutes` | `30` | Don't repeat an alert about the same chest more often than this. |
| `join-summary` | `true` | On join, tell players what golems sorted in their claims while they were away and how many of their chests filled up. |
| `find-enabled` | `true` | `/cgc find`. `false` turns the command off for everyone. |
| `find-outside-own-claims` | `false` | `false`: players' `/cgc find` only looks in claims they own. `true`: also unclaimed containers and claims where they have container trust (never other players' claims or locked containers). |
| `find-radius` | `32` | How far `/cgc find` looks, 1 to 64 blocks sideways and up or down. |
| `find-max-radius` | `64` | The most a player can ask for with `/cgc find <item> <radius>`, 1 to 128 blocks (`/cgc golems <radius>` isn't limited). Bigger requests are cut down to it. It never counts as less than `find-radius`. Ops (`coppergolemclerk.allclaims`) and the console aren't limited. |
| `find-highlight-count` | `5` | How many of the containers `/cgc find` lists glow for the player, nearest first, 0 to 30 (0: no glow). |
| `find-highlight-seconds` | `10` | How long the containers `/cgc find` lists glow for the player, 0 to 120 (0: no glow). |
| `golems-command-for-players` | `false` | Let players use `/cgc golems` to list their own golems (in claims they own, or whose copper chest is in one). Off: admins only. |

### Server

| Setting | Default | What it does |
|---|---|---|
| `disabled-worlds` | `[]` | Worlds (by folder name) where the plugin does nothing and golems behave as in vanilla, e.g. `[world_nether, world_the_end]`. |
| `update-check` | `true` | Check GitHub weekly for a newer release; log it and tell admins on join. Nothing is downloaded. |
| `debug` | `false` | Write every decision the plugin makes to the console, with the reason: chest choices and accept/refuse, furnace jobs, `[Overflow]` fetches, returns, giving way, greetings, carry top-ups, reach, portals, mode toggles, tidying, update checks. Changes nothing golems do. Players never see it. It writes several lines per golem per delivery, so turn it off after setting up or investigating. |
| `trace-golems` | `false` | Once a second, log each golem's state, position, held item and whether it's walking, when any of those changed (up to a line a second per walking golem). Even chattier than `debug`. Use it to follow exactly what golems do. |

## Permissions

| Permission | Default | Allows |
|---|---|---|
| `coppergolemclerk.find` | everyone | `/cgc find`: containers in your own claims (wider with `find-outside-own-claims`). |
| `coppergolemclerk.check` | everyone | `/cgc check` on containers you could open, or the item in your hand. |
| `coppergolemclerk.label` | everyone | `/cgc label`: a sign label suggestion for a container you could open. |
| `coppergolemclerk.overflow` | everyone | `/cgc overflow`: the `[Overflow]` chests near you that you could open. |
| `coppergolemclerk.storage` | everyone | `/cgc storage`: how full the containers near you that you could open are. |
| `coppergolemclerk.stats` | everyone | `/cgc stats`: what golems sorted in your claims. |
| `coppergolemclerk.golems` | everyone | `/cgc golems`: your own golems. Only works if `golems-command-for-players` is `true` (it's `false` by default). |
| `coppergolemclerk.toggle` | everyone | Switching golems between the plugin and vanilla with the toggle item (with GriefPrevention, only where you may build). |
| `coppergolemclerk.allclaims` | op | `find`, `check`, `overflow`, `stats` and `golems` see into every claim and locked containers. |
| `coppergolemclerk.alerts` | op | Alerts (full chests, lost golems) usually go to the owner of the claim where they happen. Outside every claim there's no owner, so they go to online players with this permission instead. Without GriefPrevention, every alert does. |
| `coppergolemclerk.teleport` | nobody | Stuck golems whose copper chest is in this player's claim may be teleported home, if `lost-golem-teleport-home` is `true`. |
| `coppergolemclerk.admin` | op | Every `/cgc` command (also `reload`, `version`, positions and `golems all`), and being told about new releases. |

## Building from source

You need Java 25. The Gradle wrapper downloads Gradle itself.

```
git clone https://github.com/mhardin18/copperGolemClerk.git
cd copperGolemClerk
./gradlew build
```

The jar is written to `build/libs/CopperGolemClerk-<version>.jar`. Install it as described in
[Install](#install).

### Adding a setting

A new setting needs all of these, or `./gradlew build` fails (`ConfigDocsTest`) and says
what's missing:

1. A line in `src/main/resources/config.yml`, with its own comment right above it. The
   comment says what the setting does, its range and default, and what raising or lowering it
   does. Servers that upgrade get the setting added to their `config.yml` from there, comment
   included. A changed comment replaces the old one above that setting in their file.
2. A row in the [settings reference](#settings-reference) in this README, and a mention in
   the section that describes the feature.
3. A line in `CHANGELOG.md` under the version that adds it, naming it in backticks.

Also update `docs/FullDescription.md` (the Hangar page) when the setting changes what
players or server owners see. A setting kept out of `config.yml` on purpose (a test hook) is
listed in `ConfigDocsTest.HIDDEN` with the reason.

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for what changed in each version.

## License

GPL-3.0. See [LICENSE](LICENSE).
