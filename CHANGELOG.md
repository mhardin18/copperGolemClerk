# Changelog

All versions are for **Paper 26.3** (Java 25); 2.6.x needs build 190 or newer. Versions before 2.0 were called SmartCopperGolems. To upgrade, replace the old `SmartCopperGolems-*.jar` in `plugins/` with the new one and restart. Your `config.yml` is kept, and new settings use their defaults until you add them.

## Unreleased

### Fixed
- On a Paper 26.3 build older than 190 (before Paper added the golem sorting event), the plugin now logs that it needs build 190 or newer and turns itself off, instead of failing with an error.

### Changed
- README, Hangar page and CHANGELOG say which Paper build is needed: 26.3 build 190 or newer.

## 2.6.0 (2026-10-30)

### New
- `/cgc golems` finds your copper golems: the 10 nearest (or every one with `all`), each with its position (admins can click to teleport), distance and direction, what it's carrying, its copper and its mode. Golems in unloaded chunks are listed where they were last seen.
- Lost golems find their way home: a golem remembers the copper chest it last took items from, and if it ends up far from any copper chest (more than 40 blocks, or 12 up or down) it walks back. A golem that can't, or is stuck in a pit, is reported to its owner with an alert. Teleporting it home is off by default (`lost-golem-teleport-home`) and also needs the claim owner to have `coppergolemclerk.teleport`. `/cgc golems` shows lost golems. Turn it off with `rescue-lost-golems: false`.
- Full-chest alerts: when a golem finds the chest labelled for its item full, or an `[Overflow]` chest is full or 90% full (`overflow-alert-percent`), the owner of the GriefPrevention claim is told (when they join, if they're offline); elsewhere, players with `coppergolemclerk.alerts` (ops by default). Alerts are logged and not repeated for `alert-cooldown-minutes` (30). Turn them off with `full-chest-alerts: false`.
- Golems stay out of portals (`avoid-portals`, on by default): a copper golem never goes through a Nether portal, End portal or End gateway, so one that wanders into a portal stays in its own world. Golems on a lead or riding go through as usual.
- Copper chest filters: an `[Only ...]` sign on a copper chest (`[Only Logs]`, `[Only Oak Log]`, `[Only #planks]`) makes golems take only those items out of it and leave the rest. Several `[Only ...]` lines add up. `/cgc check` shows the filter.
- Status labels: while you hold bamboo, copper golems near you show what they're doing (`carrying 12 birch log`, `fetching granite`, `lost, walking home`, `idle`...) and how weathered their copper is. Only players holding bamboo see them. Turn them off with `status-labels: false`.
- `/cgc stats`: how many items golems have sorted today, this week and all time: in your own claims for players, and for admins the whole server, the most-sorted items and the busiest claims. Players are told when they join what golems sorted in their claims while they were away, and how many of their chests filled up (`join-summary`).
- Barrels and shulker boxes: `destination-containers` (default `[chest, trapped_chest]`, as in vanilla) can add `barrel` and `shulker_box`. Golems then use them like chests (labels, contents, `[Overflow]`); the golem walks up and the plugin puts the item in. Leaving `chest` out keeps golems away from plain chests.
- Bigger loads: `carry-amount` (1 to 64, default 16 as in vanilla) sets how many items a golem carries per trip. After a golem takes items from a copper chest it takes more of the same from that chest (or puts some back, below 16); overflow fetches take that many too.
- `/cgc find <item>`: which containers near you hold an item (or a group, pattern or tag), nearest first, counting items in shulker boxes; the nearest 5 glow for you for 10 seconds (`find-highlight-count`, `find-highlight-seconds`). By default it only looks in GriefPrevention claims you own (`find-outside-own-claims` widens it to unclaimed containers and claims where you have container trust). Players can search once every 2 seconds. `find-enabled`, `find-radius` (32).
- Players can use `/cgc check`, `/cgc overflow` and `/cgc stats` (permissions `coppergolemclerk.check`/`.overflow`/`.stats`, everyone by default), limited to containers they could open and their own claims; `/cgc golems` too, for their own golems, if `golems-command-for-players` is on (off by default). `coppergolemclerk.allclaims` (op) sees into every claim.
- Golem limits: `max-golems-per-claim` and `max-golems-outside-claims` (per world) cap how many copper golems use the plugin; the oldest keep it, the newest over the limit sort like vanilla, and the claim owner is told. Off (0) by default. Load testing (up to 200 golems) found each working golem costs about 0.1 ms per tick with or without the plugin; the README's Golem limits section has the numbers.

### Changed
- Documentation: the README now starts with what the plugin does (golem limits included), has a feature overview, a full settings reference (every setting with its default and what it does) and a permissions table; `docs/hangar.md` is the plugin page text for Hangar (no overstated features, an invitation to suggest ideas); the `config.yml` header and `plugin.yml` description describe the plugin instead of vanilla golems.

### Fixed
- Golems on the floor above an `[Overflow]` chest never fetched from it: they were sent to the chest block itself, and the pathfinder stopped them on the floor right above the chest instead of finding the way round by the stairs. Golems now walk to a spot beside the chest they have a whole path to, and skip an overflow chest they can't get to.

## 2.5.1 (2026-10-28)

### Changed
- The green antenna light shows only to players holding the toggle item (bamboo). Golems look normal the rest of the time.

## 2.5.0 (2026-10-28)

### New
- Taller stacks of chests: `deliver-reach` and `take-reach` (default 2, vanilla's limit; up to 6) set how many chest rows up golems put items in and take items out. For chests above row 2 the golem walks to the foot of the stack and the plugin moves the items. `/cgc check` says how high a chest is and whether golems reach it.
- Switch a single golem back to vanilla: right-click it with bamboo (`golem-toggle-item`), and again to switch it back. Golems using the plugin wear a small green light on their antenna (`mode-markers`). Needs `coppergolemclerk.toggle` (everyone by default) and, with GriefPrevention, permission to build where the golem stands.

- Golems say hello: an idle copper golem walks over to a nearby iron or snow golem, waves, and goes back to its routine, then waits `greet-cooldown-minutes` (default 5) before greeting again (`greet-other-golems`, on by default).

### Changed
- The plugin checks explicitly that a golem is a copper golem before doing anything with it, so iron and snow golems (or any other mob) are never affected.

## 2.4.1 (2026-10-28)

### New
- Golem deaths are logged (`log-golem-deaths`, on by default): when a copper golem dies, the server log says where, why (lava, a fall, a mob and which one, `/kill`...) and what it was holding. A golem turning into a statue, or removed by a plugin or command, is logged too.

## 2.4.0 (2026-10-27)

### New
- Update notices: once a week the plugin checks GitHub for a newer release for your Minecraft version. A new one is logged, and admins are told when they join (once per release) with a link. Nothing is downloaded. Turn it off with `update-check: false`.
- `/cgc version` shows the installed version and checks for a newer release right away.

## 2.3.0 (2026-10-27)

### New
- Golems keep off trapdoors (`avoid-trapdoors`, on by default). Vanilla golems walk onto an open trapdoor as if it were floor and fall into the hole below; now they walk around trapdoors, open or closed. Golems stepping aside for each other avoid them too.

## 2.2.0 (2026-10-27)

### New
- A `[No Golems]` sign (any case) keeps golems away from a chest: they never put items into it, and never take items out of a copper chest that has one. Handy for a copper chest used as plain storage. `/cgc check` shows it.

### Changed
- A sign naming an item that comes in all 16 dye colours also takes the dyed ones: `[Terracotta]`, `[Shulker Box]`, `[Candle]` and `[Bundle]` now take every colour, not just the plain item. `[Red Terracotta]` still takes only red, and wins over `[Terracotta]` for red. Look-alikes stay separate: `[Sand]` doesn't take red sand, and `[Ice]` doesn't take blue ice.

## 2.1.0 (2026-10-26)

### Chests golems can't use
- Golems skip waterlogged chests. Vanilla golems stop about 4 blocks short of one and hold their item forever. `/cgc check` and `/cgc overflow` now say when a chest is waterlogged.
- Idle golems no longer take items out of `[Overflow]` chests that are locked or have a block or cat on top, since vanilla golems can't open those. Items are also never returned into a locked chest.

### Fixes
- A golem stepping aside for another no longer steps into fire, lava, water, cobwebs, powder snow, berry bushes, campfires, magma or cactus. It backs up to a safe spot instead. (One test golem burned to death and lost its 16 items.)
- Items a golem fetched from an `[Overflow]` chest are no longer lost when the golem turns into a statue.
- A sign reading `[#]` no longer causes an error each time a golem checks that chest.
- A golem far from players could hold an item with no home forever: its search restarted every 10 minutes. It now gets the item back to its copper chest as intended.
- A golem waiting beside a chest it can never reach gives the item back a minute after the normal return time.

## 2.0.0 (2026-10-25)

### Renamed: SmartCopperGolems is now Copper Golem Clerk
Another project already uses a similar name.
- The jar is `CopperGolemClerk-<version>.jar`. Delete the old `SmartCopperGolems-*.jar`, or the plugin warns that both are installed.
- Settings move to `plugins/CopperGolemClerk/`. On first start, `config.yml` is copied there from `plugins/SmartCopperGolems/`.
- The command is now `/cgc` (or `/coppergolemclerk`), replacing `/scg`.
- The permission is now `coppergolemclerk.admin`, replacing `smartcoppergolems.admin`.

### New
- `/cgc help` lists the commands; `/cgc help <command>` explains one. A bare `/cgc` or a mistyped subcommand shows the list.

### Changed
- Golems tidy a chest while they have the lid open, and again as the item drops in, instead of a few seconds after they leave.

## 1.3.0 (2026-10-25)

### New: see what golems will do
- **`/scg check`** while looking at a chest explains it: what each sign or item-frame label means, which items its contents attract, whether golems can use it (blocked, locked, full, or a hopper under it feeding a copper chest), and whether the item in your hand would go there.
- **`/scg overflow`** reports what's in the overflow chests near you and what to do about each item: add a chest (with the sign label to use), fix a chest golems can't use, make room in a full chest, or nothing, because idle golems will move it.
- From the console, both commands take coordinates: `/scg check <x> <y> <z> [world]`.
- The short command is now **`/scg`** (was `/sg`). `/smartcoppergolems` still works.

### New: `disabled-worlds`
- List worlds where golems should behave as in vanilla, for example `disabled-worlds: [creative]`. Misspelled world names get a warning at startup.

### Labels
- Hanging signs just above a chest now count as labels.
- One sign line can hold several labels: `[ice][snow]`.

### Fixes
- Two golems meeting head-on in a 1-wide corridor no longer get stuck: one backs up (up to 16 blocks) to the first spot with room to step aside.
- Golems now give way on carpet and slab floors (these were treated as walls).
- A chest signed with both a label and `[Overflow]` (for example `[logs]` and `[Overflow]`) no longer makes idle golems take its logs out and put them back over and over.
- Chest tidying now respects `disabled-worlds`.
- Clear errors for bad coordinates or world names in commands, and a warning if a group in `config.yml` is named `overflow` (signs can't use it, since `[Overflow]` always means an overflow chest).

## 1.2.0 (2026-10-25)

### New: golems tidy the chests they use
A few seconds after a golem puts something in a chest, the chest is tidied:
- Partial stacks of the same item are merged (20 + 10 oak logs become one stack of 30). Renamed or enchanted copies stay separate.
- Like items are moved next to each other: by group, in the order the groups are listed in `config.yml`, then by item. Stacks fill the chest from the first slot.

Copper chests are left alone, and so is a chest a player has open (it's tidied after they close it).

On by default. Turn it off with `tidy-chests: false` in `config.yml`.

## 1.1.0 (2026-10-25)

### New: golems give way to each other
When a golem is stuck because another golem is in its way, one of them steps one block aside and waits for the other to pass:
- A golem with empty hands gives way to one carrying an item.
- If both carry something (or both are empty), the same golem of the pair always gives way, so they never both dodge.
- Golems busy at a chest, or just passing each other, are left alone.

On by default. Turn it off with `golems-give-way: false` in `config.yml`. In a busy sorting room, giving way didn't slow sorting down.

### Fixes
- The plugin now always reports the right version number.

## 1.0.0 (2026-10-24)

First release. Copper golems sort *similar* items into the same chest (for example birch logs into a chest of oak logs), not just identical items.
- 50 built-in item groups (logs, ores, food, fuel, records, ...), configurable in `config.yml`.
- Chest labels with item frames or signs (`[group]`, an item, a pattern or a `#tag`), plus `[Overflow]` catch-all chests.
- Golems head for the best matching chest. Items with no home go to an overflow or empty chest, or back to their copper chest.
- Idle golems fetch items out of overflow chests once a proper home exists.
