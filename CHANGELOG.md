# Changelog

All versions are for **Paper 26.3** (Java 25). Version 2.6.0 and later need Paper 26.3 build 190 or newer. Versions before 2.0 were called SmartCopperGolems.

To upgrade, replace the old `SmartCopperGolems-*.jar` in `plugins/` with the new one and restart. Your `config.yml` is kept. From 3.0.0 on, settings new in a version are added to it at startup, with their explanations, and the explanations of the settings you have are updated. Before 3.0.0, new settings used their defaults until you added them.

Changes to the developer tools in `tools/` (tests and scripts that aren't part of the plugin) are in [tools/CHANGELOG.md](tools/CHANGELOG.md).

## 3.3.0 (in progress)

### New
- `/cgc settings` (admins and the console) lists the settings in `config.yml` that differ from this version's defaults: settings still at an old default (upgrading keeps your values, so a changed default stays the old one), names that aren't settings (with the nearest real one), settings no longer used or missing from the file, problems the console reported when `config.yml` was last loaded, and the settings you changed, each with its default. `/cgc settings all` lists every setting, and `/cgc settings <setting>` shows one with its explanation. Item groups aren't included.

### Changed
- `fetchers-per-overflow-chest` now defaults to 4 (was 2), and 0 means no limit. With a limit of 2, three golems and one overflow chest full of different items left one golem idle. Fetching is the last thing an idle golem does, so more fetchers only put idle golems to work. A negative value is reported in the console and 4 is used. Upgrading updates the setting's explanation but keeps your value: change 2 to 4 yourself to use the new default. The README's new "How many fetchers" section explains how many is too many.

### Fixed
- A chest lid could stay open. A golem that had just put its item in (or looked in an empty copper chest) counted as having nothing to do while the lid was still up, so it could leave to greet an iron or snow golem. Vanilla then still counted it as holding that chest open, and the lid stayed up while it stood nearby. Golems now wait until the lid closes before going to say hello. Giving way already waited for it.

## 3.2.0 (2026-11-10)

### New
- Groups can have other names. Add them under `group-aliases` in `config.yml`, as `alias: group` (for example `mechanisms: redstone`) or `alias: [group, group]`. A sign, `[Only ...]` filter or `/cgc find` with an alias means that group. A group's own name always wins, and the console warns about aliases it can't use. Empty by default. Upgrading adds `group-aliases: {}`.
- `/cgc find <item> [radius]` and `/cgc golems [radius]` take an optional search radius. From the console, use `/cgc find <item> <x> <y> <z> [world] [radius]` and `/cgc golems <x> <y> <z> [world] [radius]`.
  - `/cgc find birch log 50` looks 50 blocks around you. It searches loaded chunks only, and says how many chunks in range weren't loaded.
  - `/cgc golems 50` lists the golems within 50 blocks, nearest first. Like find, it counts along each axis. The header says "within 50 blocks".
  - A number that ends an item's name is part of it. `/cgc find music disc 13` is the disc. `/cgc find music disc 13 50` is the disc within 50 blocks.
  - Without a radius, both work as before. No radius can go past 30,000,000.
- `find-max-radius` (default 64, 1 to 128) is the most a player can ask `/cgc find` for. A larger number is cut down to it, with a note. It never counts as less than `find-radius`. Ops (`coppergolemclerk.allclaims`) and the console aren't limited. `/cgc golems` isn't limited either.
- `/cgc check` and `/cgc label` now say when golems would take an item to a more specific chest. The rule is unchanged: the sign label covering the fewest items wins, so `[ingots and gems]` beats `[*copper*]` for a copper ingot. Now the commands tell you.
  - `/cgc check` with an item in hand says which chest wins, where it is, and to add `[copper ingot]` to this chest's sign to keep the item here. If that chest is labelled for exactly the item, it says to take it off that label.
  - `/cgc label`, and the label advice at the end of `/cgc check`, list the items in the chest that go to a more specific chest. They show 5 lines at most, then "... and N more". For an unlabelled chest they check the label they suggest.
  - Nearby means within `search-distance` sideways and 8 blocks up or down. Locked, blocked, full and hopper-fed chests don't count, as when golems choose.

### Changed
- Golems take finished items out of furnaces before they go back to sorting. Before, a golem sorted every copper chest first, so cooked items sat in the furnace while there was sorting to do. Taking items out is now part of furnace upkeep and counts toward `furnace-jobs-before-sorting`, so a busy copper chest still gets sorted. `furnace-take-out-at` still decides when a furnace is worth a trip.
- A golem's home is now the last place it got work from, not only the last copper chest it took from. Lost-golem rescue uses it.
  - Home is the copper chest it took items from, the `[Overflow]` chest it fetched from, the `[Furnace Supply]` or `[Fuel]` container it took from, or the furnace it took finished items (or an empty lava bucket) out of. Chests it only delivers into never become home.
  - A golem is lost when no work place (copper chest, furnace, smoker, blast furnace, `[Overflow]`, `[Fuel]` or `[Furnace Supply]` container) is in its range and its home is more than 40 blocks away (12 up or down). Alerts name the home ("its furnace", "its [Overflow] chest"). Copper chests still read "its copper chest".
  - If the home stops being a work place (broken, sign removed), the golem forgets it.
  - A golem with no home takes the nearest work place in its range as home. With none in range it walks to the nearest one within 64 blocks (12 up or down). With none within 64 blocks it's left alone, like vanilla, until there is one.
- Greeting visits stay near the golem's work. An idle copper golem only visits an iron or snow golem within 16 blocks of its home (if that is within `search-distance`), else of its nearest copper chest or furnace, else of where it stands. It turns back if it gets more than 20 blocks from there.
  - It gives up if it can't get within reach in 20 seconds, if it gets no closer for 8 seconds, if the other golem can no longer be reached, or if that golem dies or disappears. Work turning up still ends a visit at once.
  - A visit that ends without a hello walks the golem back to the spot beside its copper chest or furnace, for up to 15 seconds. It stops if work turns up.
  - With `debug: true`, every visit that ends without a hello is logged with the reason ("stopped going to say hello: ...").
- Golems now open a chest, barrel or shulker box lid briefly whenever the plugin moves items for them. This covers:
  - fetching from an `[Overflow]` chest
  - taking from or putting into a `[Fuel]` or `[Furnace Supply]` container
  - putting an item with no home back in its copper chest
  - taking more from a copper chest for `carry-amount`, as vanilla golems do

  Before, the items just moved with a pickup sound and the lid stayed shut, so it looked as if nothing had touched the chest. Closing a lid never loads a chunk.
- Default groups cleaned up:
  - `decorations` is split into `signs`, `banners` (and banner patterns), `pottery` (sherds and the decorated pot) and `beds`.
  - `decorations` keeps shelves, cushions, copper golem statues, item frames, the painting, armor stand, bookshelves and the flower pot.
  - Mob heads and skulls moved to `rare-items`.
  - `utility-blocks` is merged into `workstations` (22 items).
  - Cherry leaves, flowering azalea leaves and the mangrove propagule moved from `flowers` to `foliage`.
  - The chorus flower moved from `flowers` to `end`.
  - The name tag moved from `tools` to `animal-gear`.
  - `stone` (81 items) is split into `stone` (47), `deepslate` (20) and `tuff` (14), so each fits in a double chest.
  - A `[deepslate]` sign now means the deepslate group without the deepslate ores. Before, it took every item with deepslate in its name.
  - Creative-only items are in no group now: budding amethyst, infested cobblestone and mossy stone bricks, the command block minecart, the knowledge book, and the copper, iron and snow golem spawn eggs.
  - Existing servers keep their groups. Upgrading doesn't rewrite `groups:`, so copy the new ones from the default `config.yml` if you want them.
  - `[utility blocks]` still works on a fresh config (it finds `workstations`). `[workstations]` works on an older config.
- Built against the newest Paper API (26.3 build 244). It still runs on build 190 and later.

### Fixed
- Golems leave a furnace's output alone when a hopper minecart is under it, as they already did for a hopper. They check again when they reach the furnace, so a hopper placed while a golem was on its way is respected too.

## 3.1.0 (2026-11-05)

### New
- Golems tend furnaces before they sort. Before, they tended furnaces only when there was nothing to sort. An idle golem now picks its next job in this order:
  - **1.** Furnace upkeep: empty furnaces that stopped because their output is full, take out empty lava buckets, bring fuel, and bring items to cook from a `[Furnace Supply]`.
  - **2.** Sort the copper chests.
  - **3.** Take finished items out of furnaces.
  - **4.** Re-sort the `[Overflow]` chests.

  While a copper chest has items, `furnace-jobs-before-sorting` (default 3) sets how many furnace jobs in a row a golem does before it sorts once. Set it to 0 to go back to the old order.
- A sign label too wide for one line can carry on to the next. Leave the `[` open and close it with `]` on a later line: `[Only light` / `sources]` is `[Only light sources]`.
  - Lines are joined with a space, or with nothing after a `-` or `_`.
  - Before, a label had to fit on one line, so some group names and most `[Only <group>]` filters couldn't be written.
  - `/cgc check` points out a label that's never closed. `/cgc label` and `/cgc overflow` say how to write a label that's too wide.
- The README lists the default groups, with their sign labels and what's in them.
- `/cgc check` on a chest now ends with sign-label advice, the same as `/cgc label`.
  - Unlabelled chest: labels for what's in it (most specific first) and which items wouldn't fit.
  - Labelled chest: anything the label doesn't cover, and a label that would cover it.
  - Empty chest: labels for the item in your hand.
  - `[Overflow]`, `[Fuel]`, `[Furnace Supply]` and copper chests get none.
  - `/cgc label` on a labelled chest now also suggests a label for what its label doesn't cover.
- `search-distance` (default 32, vanilla's range, up to 128) sets how far golems look for chests, copper chests, `[Overflow]` chests and furnaces, in blocks each way sideways. Above 32, the plugin walks golems to chests vanilla golems can't see and moves the items for them. It also lets their paths reach that far and gives longer walks more time before giving up.
- `claim-blocks-per-extra-golem` (default 0, off) gives bigger claims more golems. A claim gets one more golem than `max-golems-per-claim` for every this many claim blocks. For example, with `max-golems-per-claim` at 5 and this setting at 2500, a 9×9 claim gets 5 golems and a 100×100 claim gets 9.

### Changed
- `/cgc check` on an `[Overflow]` chest now points to `/cgc overflow`. The range in the messages and help of `/cgc overflow` and `/cgc storage` now follows `search-distance` instead of always saying 32 blocks.
- Four default groups have shorter names, so their sign labels fit on one line.
  - `smithing-templates` is now `templates`.
  - `crafting-materials` is now `materials`.
  - `saplings-and-leaves` is now `foliage`.
  - `concrete-and-terracotta` is split into `concrete` (blocks, powder, slabs and stairs) and `terracotta` (plain, dyed and glazed).
  - Old and new names both work on signs, whichever your `config.yml` has. Upgrading doesn't change your `groups:`, so `[foliage]` finds an older config's `saplings-and-leaves`, and `[concrete and terracotta]` finds both new groups.
  - Concrete and terracotta no longer count as one group when golems match a chest by its contents.
  - A `[Terracotta]` sign now takes glazed terracotta too. Before, it took plain and dyed only.
  - `/cgc label` and `/cgc overflow` suggest the short name on an older config when there is one.
- Walks the plugin sends golems on (to furnaces, supplies, far or high chests, overflow chests) no longer stop at a fixed 30 seconds or so.
  - A golem gives up when it has gone 10 seconds without getting a block closer, in a straight line or along its path. Stuck golems move on sooner, and slow ones (Slowness, soul sand, crowds) still get there.
  - A time limit that grows with `search-distance` ends any walk that never arrives: 81 seconds at 32, 132 at 64, 235 at 128.
- How long a golem keeps trying to deliver an item now comes from the walk, not a setting.
  - Each fallback step (best chest, any matching chest, `[Overflow]` or empty chests) lasts as long as walking to the farthest chest it tries, and at least 30 seconds. A bigger `search-distance` gets longer steps automatically.
  - A step with nothing new to try is skipped. An item with no home in range is put back after the 30-second vanilla step, not after two minutes.
  - `matching-chest-timeout-seconds` is gone. It's removed from `config.yml` on upgrade, and the console says why.
- Adding or changing a sign or item frame label clears the "no home" marks on chests within `search-distance`. Golems try those items again straight away, not after `homeless-retry-minutes`.
- `max-golems-outside-claims` is gone, and golems on unclaimed land are no longer limited. It shared one limit among all the unclaimed land in a world, oldest golems first. One player's golems could leave another player's golems sorting like vanilla, with no explanation. Golems also switched between the two as players came and went. The setting is removed from `config.yml` on upgrade, and the console says why. `max-golems-per-claim` is unchanged.
- Console messages about updating `config.yml` link to that version's `config.yml` on GitHub instead of pointing into the jar.
- Built against the newest Paper API (26.3 build 238). Every build also checks that the plugin compiles against build 190's API, so it keeps running on every build from 190 on.

### Fixed
- Golems look for lava cauldrons as far out as for furnaces and chests: `search-distance` sideways and 8 blocks up or down. Before, it was always 16 sideways and 4 up or down. With `search-distance` 64, a golem tended a furnace 50 blocks away but never filled its bucket at a cauldron 20 blocks away. A cauldron you place is used within 5 seconds.
- A golem on a furnace job no longer has its item put back in a copper chest partway through a long walk. An empty bucket heading for a far cauldron, or fuel heading for a far furnace, has no chest of its own. After the 30-second steps of looking for one, the golem returned it and started over.
- Golems on furnace jobs no longer use a furnace, `[Fuel]`, `[Furnace Supply]` or cauldron through a wall or floor. Before, a golem counted as there once it was within 2.5 blocks in a straight line, so one behind a wall or a floor above could take or put items through it. Now it has to stand beside the block with open space to it, as for chests.
- With `deliver-reach` above 3, golems now feed furnaces (and use `[Fuel]` and `[Furnace Supply]` containers) in rows 4 to 6. Before, they walked to the foot of the stack, couldn't reach the block, and gave up.
- With `furnace-jobs-before-sorting` low (especially 1) and a copper chest that keeps getting items, golems stopped tending furnaces almost entirely. A golem let go to sort looked idle to the plugin, got a furnace job, then picked up its load at the copper chest, which silently ended the job. Now a golem let go to sort gets no furnace job until it has picked up its load, or 15 seconds pass.
- A golem that goes over `max-golems-per-claim` now loses the plugin's path settings straight away (the longer paths for `search-distance`, and `avoid-trapdoors`). It gets them back when it's under the limit again. Before, this waited for the next reload or for the golem's chunk to load again.
- A golem walking to an `[Overflow]` chest now checks on the way that the item still has a home. If that chest filled up, was removed or was relabelled, the golem stops without taking anything, instead of carrying the items straight back.
- With `deliver-reach` or `take-reach` raised, the top row of a wall of chests built against a hillside or another wall never got its items. They went to `[Overflow]`. The ground behind the wall, level with the top chests, counted as a place to stand, so those chests looked reachable. Vanilla golems can't open them from the floor in front. Now only a spot a golem can walk to counts, and the golem is sent there. `/cgc check` on such a chest now says golems can't walk to the spot level with it, and how many rows up it is from the floor they use. Before, it said nothing was wrong.

## 3.0.0 (2026-11-02)

"The Furnaces Are Alive." Golems now run your furnaces: feeding, fueling and emptying them. Your `config.yml` is also kept up to date with every new setting and its explanation when you upgrade.

### New
- Upgrading brings your `config.yml` up to date.
  - At startup, any setting the new version has that your file doesn't is added, with its default and its comment, in the same place as in a fresh config.
  - The comment above every setting you already have is replaced with the new explanation.
  - Your values are never changed, and a note at the end of a setting's line stays.
  - The old file is kept as `config-before-<version>.yml`. The console lists what was added and which explanations changed.
  - A `config.yml` with a YAML error is left alone. One that can't be written gets a clear console warning, and new settings use their defaults.
- Golems run your furnaces (`furnace-feeding`, on by default). Label a furnace, smoker or blast furnace like a chest (`[raw iron]`) and golems keep it going.
  - Items to cook come only from `[Furnace Supply]` containers. Each load is split between all the labelled furnaces that take it.
  - Golems take only as much as the furnaces have room for. They top a furnace up once it's running low (`furnace-refill-below`, default 16).
  - A load goes to at most 2 furnaces, in shares of at least 8 (`furnace-split-min`), emptiest furnaces first.
  - A golem never brings a load or fuel to a furnace another golem is already handling.
  - If a furnace filled up in the meantime, the golem waits for room (`furnace-wait-seconds`, default 20). Anything left over goes back in the supply instead of being sorted away.
  - Fuel comes only from `[Fuel]` (or `[Fuels]`) containers (`furnace-fuel`). The default order is charcoal, coal, then lava buckets.
  - Lava buckets are used only for a furnace with a backlog of 100 or more items (`furnace-lava-min-items`), and only if another fuel is there. A bucket lasts 100 items, and a lit furnace burns it out even with nothing to cook. The backlog counts items waiting, items on the way, and the furnace's share of the matching `[Furnace Supply]` items.
  - Empty buckets are refilled at a lava cauldron. If dripping lava refills the cauldron, empty buckets wait in a `[Fuel]` container until it's full. An idle golem then fills one and puts it back.
  - Golems bring enough fuel for what's waiting plus 16 more items (`furnace-fuel-ahead`), so the next load needs no fuel trip. Fuel a golem couldn't put in goes back to a `[Fuel]` container.
  - Golems take out finished items once 8 are ready (`furnace-take-out-at`) or the furnace has stopped. That includes a furnace that can't light because something is stuck in its output slot. They sort what they take out, unless a hopper under the furnace collects it or nothing in range would take it.
  - Urgent jobs come first: a full output slot, then fuel, then items to cook, then taking out finished items.
  - A golem that can't find a path to a furnace or container tries again after 10 seconds. One that walks there for 30 seconds without arriving waits a minute.
  - A chest on a hopper that leads into a furnace (a hand-built smelter) is never used for sorting.
  - With GriefPrevention, golems use only furnaces and containers in their copper chest's claim, or where its owner has container trust (`furnace-trusted-claims`).
  - `/cgc check` on a furnace explains what golems will do and why.
  - Sign spellings, in any case: `[Fuel]`, `[Fuels]`, `[Furnace Supply]`, `[Furnace Supplies]`, `[Furnaces Supply]`. The words may be joined by a dash or underscore, such as `[Furnace-Supply]`.
- Overflow chests that a hopper fills (a smelter's output chest) are emptied less often. Golems fetch an item only once 8 of it have built up (`overflow-fetch-min`) or it has waited 2 minutes (`overflow-fetch-wait-seconds`). Before, they walked over for every ingot. Overflow chests no hopper feeds are still emptied right away. `/cgc overflow` says when a hopper fills the chest.
- Every setting in `config.yml` now has its own explanation right above it. Settings that used to share one, like the `find-*` and `max-golems-*` settings, got their own. A setting added on upgrade always comes with its comment.
- `/cgc label` suggests a sign label for the chest you're looking at, from what's in it. It suggests the item's own label, the most specific group its items share, or the group most of them fit along with the items that don't. On a labelled chest it says whether everything matches. It only suggests, and you place the sign. Players get suggestions for containers they could open (`coppergolemclerk.label`, everyone by default).
- `/cgc storage` shows how full the storage around you is, by label (or by contents for unlabelled chests), fullest first. Full and nearly full chests are flagged. It also lists `[Overflow]` chests, empty chests left, and items waiting in copper chests. Players see only containers they could open (`coppergolemclerk.storage`, everyone by default).

### Changed
- Golems fetching from an `[Overflow]` chest take up to `carry-amount` of an item across several stacks. They used to take only the first stack: 3 ingots from a chest holding 3 + 5.
- `config.yml` and the README now fully explain `debug` (every decision the plugin logs; it changes nothing golems do) and `trace-golems` (how often it logs).
- The build checks that every setting is documented in `config.yml` with its own comment, in the README settings reference, and, for settings new since this changelog started, in the CHANGELOG. "Adding a setting" in the README lists the steps.
- The documentation explains what `*`, `#` and `@` do on a sign, with examples.
  - `*` is any letters in the item's name: `[*sand]`, `[sand*]`, and `[*]` for everything.
  - `#` is a Minecraft item tag: `[#logs]`.
  - `@` is a special group: `[@fuel]`.
  - `[*]` on a chest is picked before `[Overflow]`.
- The Hangar page text moved from `docs/hangar.md` to `docs/FullDescription.md`.
- The Hangar page has an icon for every section and a pixel-art copper golem (`docs/copper-golem.png`) beside the title.

### Fixed
- `max-golems-per-claim` is now counted for each claim on its own, as documented. It used to add up all the claims of one owner. Golems in admin claims counted as unclaimed land (`max-golems-outside-claims`). Admin claims now have their own limit too.
- `/cgc golems` no longer lists a golem as "not loaded" when it was killed just before its chunk unloaded (for example `/kill`, then everyone leaving). It used to stay in the list, and in `golems.yml`, for 30 days.

## 2.6.2 (2026-10-31)

### Changed
- `/cgc find torch` still finds only torches. It now adds an "Also nearby" line naming other nearby items with the word in their name (copper torch, soul torch...) and suggests `/cgc find *torch*` to find them all. `/cgc help find`, the README and the Hangar page say so too. Searches that already match by name, pattern, tag or group get no hint.
- README and Hangar page: the "For players" lists give every player command its own line (`find`, `check`, `overflow`, `stats`, `golems` where allowed, `help`). The README gets a "For admins" list like the Hangar page's. Both give the load test result: about 0.1 ms per golem per tick, as in vanilla.

### Fixed
- The "While you were away, golems sorted N items" message counted items sorted while you were still online. It used the whole hour you left in. A server stop or restart never noted you leaving at all, because Paper disconnects players after plugins stop, so your next join counted everything since your last real logoff. The plugin now notes the running count when you leave or the server stops, and reports only what was sorted after that. Players last seen under an older version get no count on their next join, instead of a wrong one.

## 2.6.1 (2026-10-30)

### Fixed
- On a Paper 26.3 build older than 190 (before Paper added the golem sorting event), the plugin now logs that it needs build 190 or newer and turns itself off. Before, it stayed on with its sorting missing, so golems sorted like vanilla while its other features kept running. Paper logged "Failed to register events for class SortListener".

### Changed
- README, Hangar page and CHANGELOG say which Paper build is needed: 26.3 build 190 or newer.

## 2.6.0 (2026-10-30)

### New
- `/cgc golems` finds your copper golems: the 10 nearest, or every one with `all`. Each entry shows its position (admins can click to teleport), distance and direction, what it's carrying, its copper and its mode. Golems in unloaded chunks are listed where they were last seen.
- Lost golems find their way home. A golem remembers the copper chest it last took items from. If it ends up far from any copper chest (more than 40 blocks, or 12 up or down), it walks back.
  - A golem that can't, or is stuck in a pit, is reported to its owner with an alert.
  - Teleporting it home is off by default (`lost-golem-teleport-home`). It also needs the claim owner to have `coppergolemclerk.teleport`.
  - `/cgc golems` shows lost golems.
  - Turn this off with `rescue-lost-golems: false`.
- Full-chest alerts. You are told when a golem finds the chest labelled for its item full, or when an `[Overflow]` chest is full or 90% full (`overflow-alert-percent`).
  - The owner of the GriefPrevention claim is told, when they join if they're offline.
  - Elsewhere, players with `coppergolemclerk.alerts` (ops by default) are told.
  - Alerts are logged and not repeated for `alert-cooldown-minutes` (30).
  - Turn them off with `full-chest-alerts: false`.
- Golems stay out of portals (`avoid-portals`, on by default). A copper golem never goes through a Nether portal, End portal or End gateway, so one that wanders into a portal stays in its own world. Golems on a lead or riding go through as usual.
- Copper chest filters. An `[Only ...]` sign on a copper chest (`[Only Logs]`, `[Only Oak Log]`, `[Only #planks]`) makes golems take only those items out of it. Several `[Only ...]` lines add up. `/cgc check` shows the filter.
- Status labels. While you hold bamboo, nearby copper golems show what they're doing (`carrying 12 birch log`, `fetching granite`, `lost, walking home`, `idle`...) and how weathered their copper is. Only players holding bamboo see them. Turn them off with `status-labels: false`.
- `/cgc stats` shows how many items golems have sorted today, this week and all time. Players see their own claims. Admins see the whole server, the most-sorted items and the busiest claims. Players are told when they join what golems sorted in their claims while they were away, and how many of their chests filled up (`join-summary`).
- Barrels and shulker boxes. `destination-containers` (default `[chest, trapped_chest]`, as in vanilla) can add `barrel` and `shulker_box`. Golems then use them like chests, with labels, contents matching and `[Overflow]`. The golem walks up and the plugin puts the item in. Leaving `chest` out keeps golems away from plain chests.
- Bigger loads. `carry-amount` (1 to 64, default 16 as in vanilla) sets how many items a golem carries per trip. After taking items from a copper chest, the golem takes more of the same from that chest, or puts some back if it's below 16. Overflow fetches take that many too.
- `/cgc find <item>` shows which containers near you hold an item (or a group, pattern or tag), nearest first. It counts items in shulker boxes. The nearest 5 glow for you for 10 seconds (`find-highlight-count`, `find-highlight-seconds`).
  - By default it only looks in GriefPrevention claims you own. `find-outside-own-claims` widens it to unclaimed containers and claims where you have container trust.
  - Players can search once every 2 seconds.
  - Other settings: `find-enabled`, `find-radius` (32).
- Players can use `/cgc check`, `/cgc overflow` and `/cgc stats` (permissions `coppergolemclerk.check`, `.overflow` and `.stats`, everyone by default). They are limited to containers they could open and their own claims. `/cgc golems` works too, for their own golems, if `golems-command-for-players` is on (off by default). `coppergolemclerk.allclaims` (op) sees into every claim.
- Golem limits. `max-golems-per-claim` and `max-golems-outside-claims` (per world) cap how many copper golems use the plugin. The oldest golems keep it. The newest ones over the limit sort like vanilla, and the claim owner is told. Off (0) by default. Load testing with up to 200 golems found each working golem costs about 0.1 ms per tick, with or without the plugin. The README's Golem limits section has the numbers.

### Changed
- Documentation:
  - The README now starts with what the plugin does, including golem limits. It has a feature overview, a full settings reference (every setting with its default) and a permissions table.
  - `docs/hangar.md` is the Hangar page text, with no overstated features and an invitation to suggest ideas.
  - The `config.yml` header and the `plugin.yml` description describe the plugin instead of vanilla golems.

### Fixed
- Golems on the floor above an `[Overflow]` chest never fetched from it. They were sent to the chest block itself, and the pathfinder stopped them on the floor above instead of finding the way round by the stairs. Golems now walk to a spot beside the chest that they have a whole path to, and skip an overflow chest they can't get to.

## 2.5.1 (2026-10-28)

### Changed
- The green antenna light shows only to players holding the toggle item (bamboo). Golems look normal the rest of the time.

## 2.5.0 (2026-10-28)

### New
- Taller stacks of chests. `deliver-reach` and `take-reach` (default 2, vanilla's limit, up to 6) set how many chest rows up golems put items in and take items out. For chests above row 2, the golem walks to the foot of the stack and the plugin moves the items. `/cgc check` says how high a chest is and whether golems reach it.
- Switch a single golem back to vanilla by right-clicking it with bamboo (`golem-toggle-item`). Right-click again to switch it back. Golems using the plugin wear a small green light on their antenna (`mode-markers`). This needs `coppergolemclerk.toggle` (everyone by default) and, with GriefPrevention, permission to build where the golem stands.
- Golems say hello. An idle copper golem walks over to a nearby iron or snow golem, waves, and goes back to its routine. It then waits `greet-cooldown-minutes` (default 5) before greeting again. Turn this off with `greet-other-golems` (on by default).

### Changed
- The plugin checks that a golem is a copper golem before doing anything with it, so iron and snow golems and other mobs are never affected.

## 2.4.1 (2026-10-28)

### New
- Golem deaths are logged (`log-golem-deaths`, on by default). When a copper golem dies, the server log says where, why (lava, a fall, a mob and which one, `/kill`...) and what it was holding. A golem turning into a statue, or removed by a plugin or command, is logged too.

## 2.4.0 (2026-10-27)

### New
- Update notices. Once a week the plugin checks GitHub for a newer release for your Minecraft version. A new one is logged, and admins are told when they join (once per release) with a link. Nothing is downloaded. Turn it off with `update-check: false`.
- `/cgc version` shows the installed version and checks for a newer release right away.

## 2.3.0 (2026-10-27)

### New
- Golems keep off trapdoors (`avoid-trapdoors`, on by default). Vanilla golems walk onto an open trapdoor as if it were floor and fall into the hole below. Now they walk around trapdoors, open or closed. Golems stepping aside for each other avoid them too.

## 2.2.0 (2026-10-27)

### New
- A `[No Golems]` sign (any case) keeps golems away from a chest. They never put items into it, and never take items out of a copper chest that has one. Handy for a copper chest used as plain storage. `/cgc check` shows it.

### Changed
- A sign naming an item that comes in all 16 dye colours also takes the dyed ones. `[Terracotta]`, `[Shulker Box]`, `[Candle]` and `[Bundle]` now take every colour, not just the plain item. `[Red Terracotta]` still takes only red, and wins over `[Terracotta]` for red. Look-alikes stay separate: `[Sand]` doesn't take red sand, and `[Ice]` doesn't take blue ice.

## 2.1.0 (2026-10-26)

### Chests golems can't use
- Golems skip waterlogged chests. Vanilla golems stop about 4 blocks short of one and hold their item forever. `/cgc check` and `/cgc overflow` now say when a chest is waterlogged.
- Idle golems no longer take items out of `[Overflow]` chests that are locked or have a block or cat on top, since vanilla golems can't open those. Items are also never returned into a locked chest.

### Fixes
- A golem stepping aside for another no longer steps into fire, lava, water, cobwebs, powder snow, berry bushes, campfires, magma or cactus. It backs up to a safe spot instead. (One test golem burned to death and lost its 16 items.)
- Items a golem fetched from an `[Overflow]` chest are no longer lost when the golem turns into a statue.
- A sign reading `[#]` no longer causes an error each time a golem checks that chest.
- A golem far from players could hold an item with no home forever, because its search restarted every 10 minutes. It now gets the item back to its copper chest as intended.
- A golem waiting beside a chest it can never reach gives the item back a minute after the normal return time.

## 2.0.0 (2026-10-25)

### Renamed: SmartCopperGolems is now Copper Golem Clerk
Another project already uses a similar name.
- The jar is `CopperGolemClerk-<version>.jar`. Delete the old `SmartCopperGolems-*.jar`, or the plugin warns that both are installed.
- Settings move to `plugins/CopperGolemClerk/`. On first start, `config.yml` is copied there from `plugins/SmartCopperGolems/`.
- The command is now `/cgc` (or `/coppergolemclerk`), replacing `/scg`.
- The permission is now `coppergolemclerk.admin`, replacing `smartcoppergolems.admin`.

### New
- `/cgc help` lists the commands. `/cgc help <command>` explains one. A bare `/cgc` or a mistyped subcommand shows the list.

### Changed
- Golems tidy a chest while they have the lid open, and again as the item drops in, instead of a few seconds after they leave.

## 1.3.0 (2026-10-25)

### New: see what golems will do
- **`/scg check`** while looking at a chest explains it. It shows what each sign or item-frame label means, which items its contents attract, and whether golems can use it (blocked, locked, full, or a hopper under it feeding a copper chest). It also says whether the item in your hand would go there.
- **`/scg overflow`** reports what's in the overflow chests near you and what to do about each item. The answer is one of: add a chest (with the sign label to use), fix a chest golems can't use, make room in a full chest, or nothing, because idle golems will move it.
- From the console, both commands take coordinates: `/scg check <x> <y> <z> [world]`.
- The short command is now **`/scg`** (was `/sg`). `/smartcoppergolems` still works.

### New: `disabled-worlds`
- List worlds where golems should behave as in vanilla, for example `disabled-worlds: [creative]`. Misspelled world names get a warning at startup.

### Labels
- Hanging signs just above a chest now count as labels.
- One sign line can hold several labels: `[ice][snow]`.

### Fixes
- Two golems meeting head-on in a 1-wide corridor no longer get stuck. One backs up (up to 16 blocks) to the first spot with room to step aside.
- Golems now give way on carpet and slab floors. These were treated as walls.
- A chest signed with both a label and `[Overflow]` (for example `[logs]` and `[Overflow]`) no longer makes idle golems take its logs out and put them back over and over.
- Chest tidying now respects `disabled-worlds`.
- Commands give clear errors for bad coordinates or world names. The plugin warns if a group in `config.yml` is named `overflow`, since signs can't use it: `[Overflow]` always means an overflow chest.

## 1.2.0 (2026-10-25)

### New: golems tidy the chests they use
A few seconds after a golem puts something in a chest, the chest is tidied:
- Partial stacks of the same item are merged (20 + 10 oak logs become one stack of 30). Renamed or enchanted copies stay separate.
- Like items are moved next to each other, by group in the order the groups are listed in `config.yml`, then by item. Stacks fill the chest from the first slot.

Copper chests are left alone, and so is a chest a player has open. That one is tidied after they close it.

On by default. Turn it off with `tidy-chests: false` in `config.yml`.

## 1.1.0 (2026-10-25)

### New: golems give way to each other
When a golem is stuck because another golem is in its way, one of them steps one block aside and waits for the other to pass.
- A golem with empty hands gives way to one carrying an item.
- If both carry something, or both are empty, the same golem of the pair always gives way, so they never both dodge.
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
