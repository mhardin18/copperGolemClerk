# Copper Golem Clerk

**Turn copper golems into a storage staff that actually keeps your base in order.**

Drop anything into a copper chest and your golems file it away: birch logs go in with the
oak logs, a diamond pickaxe goes to the tools, and the stack of mixed loot from your last
mining trip ends up in a dozen tidy chests. Label chests with a sign like `[logs]` or `[Ores]`,
or just put one item in a chest to say what it's for. Golems pick the right chest, walk
straight to it, keep the chests tidy, and tell you when one is full.

A Paper plugin for **Paper 26.3**, build 190 or newer (Java 25). Works out of the box with about 50 item groups
that cover nearly every survival item; everything else is optional.

## Features

**Sorting that understands your storage**
- **Groups:** a golem carrying birch logs uses the chest of oak logs, a stew goes with the
  food, a pickaxe with the tools. About 50 groups ship ready to use; edit or add your own.
- **Sign and item frame labels:** `[logs]`, `[oak log]`, `[*_ORE]`, `[#planks]`, or an item
  in a frame. The most specific chest wins: `[stews]` beats `[food]` for a stew.
- **`[Overflow]` chests** catch anything without a home, and idle golems move items back out
  once you add a chest for them.
- **`[No Golems]`** keeps golems out of a chest; **`[Only ...]`** on a copper chest makes
  golems take only some items out of it.
- Golems **walk straight to the right chest** instead of dropping things in the first
  empty one, and **put back** what they can't place.
- **Tidy chests:** partial stacks merged and like items kept together.

**Bigger, faster storage rooms**
- **Taller stacks** (a setting): golems reach chests up to 6 rows high (vanilla: 2).
- **Barrels and shulker boxes** as destinations (a setting).
- **Bigger loads** (a setting): carry up to 64 items a trip instead of 16.
- **Golems give way** to each other in narrow aisles instead of getting stuck.

**Golems that look after themselves**
- **Lost golems walk home**, and you're told where one is if it's stuck.
- Golems **keep off trapdoors** and **stay out of portals**.
- Every golem death is logged with its cause, so none go missing unexplained.

**For players**
- **`/cgc find <item>`:** which chests near you hold something, and the nearest glow for you.
  By default players only search their own GriefPrevention claims.
- **`/cgc check`:** explains a chest you could open, or which groups the item in your hand is in.
- **`/cgc overflow`:** what's piling up in the overflow chests near you, and what label would
  give each item a home.
- **`/cgc stats`:** what golems have sorted in your claims today, in the last 7 days and all time.
- **`/cgc golems`:** your own golems and where they are (only with `golems-command-for-players`).
- **`/cgc help`:** the commands, and `/cgc help <command>` for one.
- **Full-chest alerts** to the claim owner, even if they're offline.
- **Status labels:** hold bamboo to see what each golem is doing.
- A **join summary** of what golems sorted while you were away.
- Right-click a golem with bamboo to switch it **back to vanilla**.

**For admins**
- **`/cgc check`** explains any chest: what its labels mean, what it takes, and anything
  stopping golems from using it.
- **`/cgc overflow`** lists what's in overflow chests and what label would give each item a home.
- **`/cgc stats`** shows how much the golems have sorted, server-wide.
- **`/cgc golems`** finds every golem: where it is, what it's carrying and whether it's lost.
- **Golem limits** (optional, off by default): cap how many golems in one claim use the
  plugin.
- **Load tested** with up to 200 golems: a working golem costs about 0.1 ms per tick, the same
  as in vanilla.

Every feature has a setting; see [Settings reference](#settings-reference).

## Requirements

- A [Paper](https://papermc.io/downloads/paper) server, version **26.3**, build **190** or newer
  (the build that added the golem sorting event; on older builds the plugin logs why and turns itself off)
- Java **25**

Spigot and Bukkit aren't supported: the plugin uses Paper-only golem events.

## Install

1. Download `CopperGolemClerk-<version>.jar` from the
   [latest release](https://github.com/mhardin18/copperGolemClerk/releases/latest).
   Release tags name the Paper version they're for (e.g. `v1.0.0-paper26.3`).
2. Put the jar in your server's `plugins/` folder.
3. Restart the server (don't use `/reload`).
4. Check the console for `Enabling CopperGolemClerk`, or run `/plugins` in game.

On first start the plugin creates `plugins/CopperGolemClerk/config.yml`. The
defaults work as-is: about 50 groups that cover nearly every survival item. After editing
the file, run `/cgc reload` to apply it without a restart.

To update, replace the old jar with the new one and restart. Your `config.yml` is
kept. To uninstall, delete the jar (and the `plugins/CopperGolemClerk/` folder if
you don't want to keep the config).

### Upgrading from SmartCopperGolems

This plugin was called SmartCopperGolems until version 2.0. To upgrade, delete
`SmartCopperGolems-*.jar` from `plugins/`, add `CopperGolemClerk-<version>.jar` and
restart. On first start your `config.yml` is copied from `plugins/SmartCopperGolems/`
to `plugins/CopperGolemClerk/`; after that you can delete the old folder. The
command is now `/cgc` (or `/coppergolemclerk`), and the permission is
`coppergolemclerk.admin`: if you granted `smartcoppergolems.admin` to anyone, grant
the new one instead.

### Quick start

1. Place a copper chest and fill it with mixed items.
2. Place a few chests nearby (within 32 blocks sideways, 8 up or down). Put one
   item in each to say what goes there, or label it with a sign like `[logs]` or
   an item frame.
3. Build a copper golem (a carved pumpkin on top of a copper block) next to the
   copper chest.
4. Stay nearby: golems only work in loaded chunks, and Paper slows them down
   when no player is close.

The golem now puts birch logs in your oak log chest, a diamond pickaxe in your
tools chest, and so on.

## How it works

A copper golem takes items out of a copper chest and looks for a chest to put them in.
On its own it only uses a chest that already holds that exact item, or an empty one.
With this plugin it also uses a chest whose items share a **group** with what it
carries, or a chest labelled for it, and it chooses the best of those in range.

Groups are defined in `config.yml` (and you can edit them) using:

| Entry           | Meaning                                    |
|-----------------|--------------------------------------------|
| `DIAMOND`       | one item                                   |
| `*_ORE`         | name pattern                               |
| `#minecraft:logs` | vanilla or datapack item tag             |
| `@edible`, `@fuel`, `@record` | item properties              |

### Smarter use of empty chests

Vanilla golems walk to the nearest chest they haven't tried and drop their item
into the first empty chest they reach, often before finding the chest where it
belongs. After trying 10 chests they pause and start over from the nearest, so
far-away chests seldom get used.

With `prefer-matching-chests` (on by default), a golem carrying an item first
looks over every chest in its range: 32 blocks sideways and 8 up or down, in
loaded chunks. If any chest matches the item (a sign or frame label, the same item,
or the same group) and has room, the golem walks straight to it. Empty chests are
used only when no matching chest has room. If the golem hasn't delivered within
`matching-chest-timeout-seconds` (default 30), for example because the chest is
walled off, it works through the fallback steps in "Items with no home" below.

### Item frame labels

Hang an item frame (or glow item frame) with an item in it on any side of a
chest, and golems put **only that exact item** in that chest. A label overrides
groups and the empty-chest rule. Several frames on one chest allow each framed
item; empty frames are ignored. Turn this off with `item-frame-labels: false`.

### Sign labels and overflow chests

Put a wall sign on any side of a chest (or a standing sign on top, or a hanging
sign just above) with rules in square brackets. Each line can hold one rule or
several (`[ice][snow]`), and the chest takes anything any rule matches:

| Line        | Accepts                         |
|-------------|---------------------------------|
| `[logs]`    | the `logs` group from the config |
| `[oak log]` | that one item                    |
| `[*_ORE]`   | items matching the pattern       |
| `[#planks]` | items in that tag                |
| `[Overflow]`| anything with no other home      |
| `[No Golems]`| nothing: golems leave it alone  |
| `[Only Logs]`| on a copper chest: golems take only logs out |

Case doesn't matter and spaces count as underscores. A group name wins over an
item with the same name, and group names work singular or plural (`[Rail]` =
`rails`, `[ore]` = `ores`, `[light source]` = `light-sources`). If a word matches
no group and no item, it matches items with that word in their name (`[carpet]`
= every carpet). An item that comes in all 16 dye colours also covers them:
`[Terracotta]`, `[Shulker Box]`, `[Candle]` and `[Bundle]` take the plain item and
every colour, while `[Red Terracotta]` takes only red. For one exact item that
shares a group's name, use a framed item instead.

A `[No Golems]` sign (any case, `[no golem]` works too) keeps golems away from a
chest completely: they never put anything into it, and on a copper chest they never
take anything out. Other labels on the same chest are ignored. Use it for a copper
chest you want as plain storage.

An `[Only ...]` sign on a copper chest is a filter: golems take only those items out
of it and leave the rest. After `Only` comes any label from the table above, so
`[Only Logs]`, `[Only Oak Log]`, `[Only *_ORE]` and `[Only #planks]` all work, and
several `[Only ...]` lines add up. Handy for a shared input chest where you keep a few
things you don't want sorted. `/cgc check` on the copper chest shows the filter and
how many items it's leaving behind. On a chest that isn't a copper chest it does nothing.

When several chests would take an item, golems use the most specific one. Labels
(frames and signs) come before contents: a framed item first, then the sign label
covering the fewest items, then a chest already holding that exact item, then a
group match by contents. A stew goes to `[stews]` before `[food]`, and a `[logs]`
chest wins over an unlabelled chest that happens to hold birch logs. Frame and sign labels on one chest combine.

Where a golem takes an item, in order: a matching chest with room (label, same
item or group), then an `[Overflow]` chest, then an empty chest
(`overflow-before-empty: false` swaps the last two). Overflow chests never count
as a match for what's already in them.

Golems never use a chest that vanilla golems can't: one with a solid block or a
sitting cat on top, a locked one, or a waterlogged one (vanilla golems walk up to it
and wait there for good). They also skip chests that drain through hoppers into a
copper chest, so items don't go round in circles. `/cgc check` on a chest tells you
if any of these applies.

### Items with no home

If a golem can't deliver its item it works through four steps, each lasting
`matching-chest-timeout-seconds` (default 30): steer to the most specific matching
chest, overflow or empty chest; then any matching chest (the most specific one may be
unreachable); then only overflow or empty chests; then plain vanilla wandering. After
that (with `return-unsortable-items`, on by default), the item is put back into the copper chest it came from (golems can't walk to
copper chests while carrying, so it's moved directly) and marked "no home" there for
`homeless-retry-minutes` (default 5). Golems take other items first and skip a copper
chest that has only "no home" items left. Add a home for the item, then
`/cgc reload` to have golems try again right away. Steps count only time the
golem is likely active (walking, or a player within the server's entity activation
range), so golems that Paper pauses far from players don't run out of time, and a
golem waiting its turn next to a chest that takes its item isn't sent back (unless it
is still waiting a minute after the last step, which means it can't use that chest).

### Re-sorting the overflow chest

With `fetch-from-overflow` (on by default), a golem with nothing to do (empty
hand, nothing to take from any copper chest in range) walks to an `[Overflow]`
chest and takes up to `carry-amount` (16) of an item that now has a home, then sorts it. Up to
`fetchers-per-overflow-chest` golems (default 2) fetch from one overflow chest
at a time, each a different item, so they don't crowd it. So when
you add a chest for something that was piling up in overflow, golems move it
over on their own. Items with no home are left in overflow, and so are items in
an overflow chest golems can't open (locked, blocked or waterlogged). Golems never walk
to an empty copper chest.

### Bigger loads

Vanilla golems carry 16 items per trip. `carry-amount` (1 to 64, default 16) changes
that: right after a golem takes items out of a copper chest, it takes more of the same
item from that chest, or puts the extra back if the setting is under 16. An item that
stacks to less (16 ender pearls, one tool) is carried that many at most. At 64 a big
sorting job needs a quarter of the trips. Items held back by `[Only ...]` or returned as
homeless aren't added.

### Golems give way to each other

Golems working in a crowded room or a narrow aisle can end up nose to nose,
pushing against each other. With `golems-give-way` (on by default), when a golem
is stuck (trying to walk but barely moving for a second) because another golem
is in its way, one of them steps one block aside and waits for the other to pass:

- A golem with empty hands gives way to one carrying an item.
- If both carry something, or both are empty, the same golem of the pair always
  gives way, so they never both dodge.
- A golem busy at a chest never steps aside, and golems that are just passing
  each other are left alone.
- A golem never steps aside into fire, lava, water, a bubble column, a cobweb, powder
  snow, a berry bush, a wither rose, pointed dripstone, a cactus or a trapdoor, or onto
  a campfire, magma block or trapdoor; it backs up to a safe spot instead.

### Golems keep off trapdoors

Vanilla golems treat an open trapdoor as floor, so a trapdoor over a hole (a ladder
shaft, a drop into a farm) catches them: they walk onto it and fall in. With
`avoid-trapdoors` (on by default), golems plan their paths around trapdoors, open or
closed, and never stand on one. A golem whose only way to a chest is across a
trapdoor won't reach it, and its item goes elsewhere as usual.

### Golems stay out of portals

A golem that wanders into a Nether portal ends up in the Nether, far from its copper
chest and unable to get back. With `avoid-portals` (on by default), golems never go
through a Nether portal, End portal or End gateway; one that steps into a portal just
stays in its own world. A golem on a lead or riding a boat or minecart goes through
as usual, so you can still take golems to another dimension.

### Taller stacks of chests

Vanilla golems only open chests up to 2 rows above the floor they stand on: the
chest at their feet and the one on top of it. Two settings raise that, up to 6 rows:

- `deliver-reach` (default 2): how high golems put items into chests (labelled,
  matching, `[Overflow]` and empty chests).
- `take-reach` (default 2): how high golems take from copper chests, and from
  `[Overflow]` chests when fetching.

For a chest above row 2, the golem walks to the floor in front of the stack and the
plugin opens the chest and moves the items, as the golem would. It needs floor to
stand on beside the stack (standing on top of other chests doesn't count). A chest
within vanilla's reach is still preferred over an equally good one higher up.
`/cgc check` on a high chest says how many rows up it is and whether golems reach it.

### Barrels and shulker boxes

Vanilla golems only put items into chests and trapped chests. List more containers in
`destination-containers` and golems use those as well:

```yaml
destination-containers: [chest, trapped_chest, barrel, shulker_box]
```

`shulker_box` covers every colour. Barrels and shulker boxes work like chests: sign
and frame labels, matching by contents, `[Overflow]` and `[No Golems]` all apply.
Vanilla golems can't open them, so the golem walks up to one and the plugin puts the
item in (as for chests above vanilla's reach). A shulker box needs open space in front
of its lid, and never takes a shulker box. Leave `chest` out of the list to keep golems
away from plain chests.

An empty barrel or shulker box counts as an empty chest: with `allow-empty-chests` on,
items with no other home can end up in one. Put a `[No Golems]` sign on barrels you
use for something else.

### Switching a golem back to vanilla

Right-click a copper golem with bamboo (`golem-toggle-item`) to switch that golem to
plain vanilla behavior, and again to switch it back. While you hold bamboo, golems
using this plugin show a small green light on their antenna; vanilla golems don't.
Golems look normal to everyone else. A vanilla golem gets none
of the plugin's features: no smart sorting or labels, no overflow, returns, fetching,
trapdoor avoidance, taller reach, giving way or tidying. The mode is saved on the
golem.

While you hold bamboo, every copper golem within 48 blocks also shows a small label
saying what it's doing: `carrying 12 birch log`, `fetching granite`, `no chest for 3
kelp`, `taking 5 kelp back`, `giving way`, `saying hello`, `lost, walking home`,
`vanilla mode`, `over the golem limit: vanilla` or `idle`. Once its copper starts to weather, a second line says how far
along it is (`oxidized: may turn into a statue` in red). Nobody else sees the labels.
Turn them off with `status-labels: false`.

Anyone with `coppergolemclerk.toggle` (everyone, by default) can switch golems; where
GriefPrevention is installed, only players allowed to build where the golem stands.
Set `golem-toggle-item: none` to turn switching off, and `mode-markers: false` to hide
the lights.

The plugin only ever changes copper golems: iron and snow golems are never affected.

### Golems say hello

A copper golem with nothing to do (empty hands, nothing to fetch, no copper chest in
range with anything to sort) walks over to an iron or snow golem within 16 blocks and
says hello: they face each other, it waves, and hearts float up. Then it goes back to
its routine and doesn't greet again for `greet-cooldown-minutes` (default 5). It
never follows: if it can't get there in 20 seconds it gives up, and any work turning
up ends the visit at once. Turn it off with `greet-other-golems: false`.

### Lost golems find their way home

Each golem remembers the copper chest it last took items from. If it ends up far from
any copper chest (more than 40 blocks from its own, or 12 up or down, say after falling
down a shaft), it walks back. If it gets no closer for 20 seconds, or it's stuck where
it can't walk to a copper chest (in a pit, walled in), its owner gets an alert (see
"Full-chest alerts" below for who that is) saying where it is:

> [Golem Clerk] A copper golem is lost at -757, -55, 102, 118 blocks from its copper chest at -760, 63, 98, and can't find a way back.

It tries again every 5 minutes. A golem moved somewhere with its own
copper chest (on a lead, say) adopts that chest as its new home; golems on a lead are
left alone. `/cgc golems` shows which golems are lost. Turn it off with
`rescue-lost-golems: false`.

Teleporting stuck golems home is possible but off by default: it needs
`lost-golem-teleport-home: true` *and* the `coppergolemclerk.teleport` permission (nobody
has it until you grant it, e.g. with LuckPerms) for the owner of the claim the golem's
copper chest is in. Golems whose chest isn't in a claim are never teleported.

### Full-chest alerts

When a golem can't put an item in the chest labelled for it because the chest is full,
or an `[Overflow]` chest is full or nearly (`overflow-alert-percent`, default 90% of its
slots), the plugin says so:

> [Golem Clerk] The [logs] chest at 120, 64, -40 is full: golems can't put birch log in it.

Who hears it:

- **In a GriefPrevention claim:** the claim's owner, and nobody else. An owner who's
  offline is told when they next join.
- **Outside every claim** (or on a server without GriefPrevention): there's no owner to
  tell, so it goes to the players online with `coppergolemclerk.alerts` (ops by default),
  typically the server's staff.

Every alert is also logged, and the same chest isn't mentioned again for
`alert-cooldown-minutes` (default 30). Turn them off with `full-chest-alerts: false`.

### Golems tidy the chests they use

With `tidy-chests` (on by default), a golem tidies a chest while it has the lid open
to put something in, and again as the item drops in, so the new item sits with its
kind:

- Partial stacks of the same item are merged (two stacks of 20 and 10 oak logs
  become one stack of 30). Items that differ, such as a renamed or enchanted copy,
  stay separate.
- Like items are moved next to each other: by group, in the order the groups are
  listed in `config.yml`, then by item. Stacks fill the chest from the first slot.

Copper chests are left alone, and so is a chest a player has open (it's tidied
after they close it).

### Golem limits

Two settings cap how many copper golems use this plugin (both 0, no limit, by default):

- `max-golems-per-claim`: golems in one GriefPrevention claim.
- `max-golems-outside-claims`: golems on unclaimed land, per world (without
  GriefPrevention, every golem in the world).

The golems are recounted every few seconds. The oldest keep the plugin; the newest ones over
the limit sort like vanilla, and the claim's owner (or, outside claims, players with
`coppergolemclerk.alerts`) is told. Golems a player switched to vanilla don't count.

**Tick cost.** A working copper golem costs about 0.1 ms per tick, whether it uses this plugin
or sorts like vanilla: the cost is the golem's own AI and pathfinding, and the plugin adds
nothing measurable on top. Measured on Paper 26.3 with 0 to 200 golems all sorting at once:

| Golems | With the plugin | Vanilla |
|---|---|---|
| 20 | 3.9 ms | 3.5 ms |
| 50 | 5.3 ms | 6.4 ms |
| 100 | 12.2 ms | 10.5 ms |
| 150 | 17.0 ms | 17.9 ms |
| 200 | 21.4 ms | 19.4 ms |

(1.7 ms with no golems; a server starts to lag at 50 ms per tick. Your numbers depend on your
hardware.) Golems over the limit keep sorting like vanilla, so the limits don't lower tick
time: they cap how many golems get this plugin's sorting. To limit the tick cost itself, limit
the number of golems.

Every setting is listed in [Settings reference](#settings-reference) below.

## Update notices

Once a week, and a minute after the server starts, the plugin asks GitHub for its
list of releases. If there's a newer version built for your server's Minecraft
version, it's written to the server log, and players with `coppergolemclerk.admin`
are told when they join (once per release), with a link to the release page. A
release that needs a newer Minecraft version is mentioned in the log only.

Nothing is downloaded or installed, and nothing is sent besides the request for the
release list. `/cgc version` checks right away. Turn it off with `update-check: false`.

## Commands

Alias `/cgc`. Players can use `help`, `find`, `check`, `overflow` and `stats` (and `golems`,
if `golems-command-for-players` is on), and only ever see their own claims or containers
they could open; see [Permissions](#permissions). Everything else, and the forms with a
position or `all`, are for admins (`coppergolemclerk.admin`, op by default).

- `/cgc help`: list the commands. `/cgc help <command>` explains one.
- `/cgc reload`: reload `config.yml`.
- `/cgc check` while looking at a chest: what each label on it means (`[Horse]` =
  items with "horse" in their name, 9 items: ...), labels that match nothing, what
  the chest takes, whether it takes the item in your hand, and anything stopping
  golems from using it (a block on top, a lock, a hopper feeding a copper chest, full).
  Players can only check containers they could open.
- `/cgc check` while not looking at a chest: which groups the item in your hand belongs to.
- `/cgc overflow`: what's in the `[Overflow]` chests within golem range of you, split into
  items with no chest (with a sign label to use for each group), items whose chest is
  full, and items golems will move on their own. Players see only the `[Overflow]` chests
  they could open.
- `/cgc golems`: the 10 copper golems nearest you, with their position (admins can click
  it to teleport there), distance and direction, what they carry, their copper and their
  mode. Players (only with `golems-command-for-players: true`) see just their own golems:
  in claims they own, or whose copper chest is in one (without GriefPrevention: the golems
  within 48 blocks of them).
  `/cgc golems all` lists every golem in every world. Golems in unloaded chunks are
  listed where they were last seen (the plugin keeps every golem's last spot in
  `golems.yml`, and forgets golems not seen for 30 days).
- `/cgc stats`: how many items golems have put into chests in your claims today, in the
  last 7 days and all time. Admins (`coppergolemclerk.allclaims`) also see the whole
  server's numbers, the most-sorted items and the busiest claims. Without GriefPrevention
  there are no claims, so everyone sees the server's numbers. With `join-summary` (on by
  default) players are told when they join what golems sorted in their claims while they
  were away and how many of their chests filled up. Numbers are kept in `stats.yml`.
- `/cgc version`: the installed version, and whether a newer release is out (checks now).
- `/cgc find <item>`: which containers near you hold an item, nearest first, with how
  many in each (items inside shulker boxes count too), and the nearest
  `find-highlight-count` (5) glow for you for `find-highlight-seconds` (10). Players can
  search once every 2 seconds. Search for anything a sign label takes: an item
  (`birch log`), a group (`logs`), a pattern (`*_log`) or a tag (`#minecraft:logs`). A word
  that names one item finds just that item (`torch` is the torch, not copper or soul
  torches); under the results, an "Also nearby" line lists other items near you with the
  word in their name and suggests `*torch*` to find them all. It
  looks `find-radius` blocks (32) around you, sideways and up or down. **By default it only looks in GriefPrevention
  claims you own** (never in locked containers); nothing hints at anything else.
  `find-outside-own-claims: true` also lets players find things in unclaimed containers
  and in claims where they have container trust; other players' claims stay hidden
  either way. Without GriefPrevention there are no claims, so players find nothing unless
  that's on. Ops see everything (`coppergolemclerk.allclaims`). Turn the command off with
  `find-enabled: false`.

From the console, give a position: `/cgc check <x> <y> <z> [world]`,
`/cgc overflow <x> <y> <z> [world]`, `/cgc golems <x> <y> <z> [world]` (the 10 golems
nearest that spot; without a position the console lists every golem),
`/cgc find <item> <x> <y> <z> [world]`.

## Settings reference

All settings live in `plugins/CopperGolemClerk/config.yml`. After editing it, run
`/cgc reload` (no restart needed). A setting missing from the file uses its default, so
an older `config.yml` keeps working after an update. A mistake in the file is reported
with its line number and the old settings are kept.

### Sorting

| Setting | Default | What it does |
|---|---|---|
| `match-mode` | `all` | When a chest counts as a group match by its contents. `all`: every item in the chest must share a group with the carried item, so one stray item stops group matching and chests stay tidy. `any`: one item from the group is enough. |
| `prefer-matching-chests` | `true` | A golem first looks over every chest in range (32 blocks sideways, 8 up or down) and walks straight to the best one that has room, instead of trying the nearest chests one by one and dropping its item in the first empty one. |
| `matching-chest-timeout-seconds` | `30` | How long each fallback step lasts when a golem can't deliver: best chest → any matching chest → `[Overflow]` or empty chests → vanilla behaviour → put it back. Only time when the golem is likely active counts. |
| `return-unsortable-items` | `true` | After the last step, put the item back into the copper chest it came from (or the nearest one with room), instead of carrying it around for ever. |
| `homeless-retry-minutes` | `5` | Items put back are marked "no home" in that copper chest for this long: golems take other items first and skip a chest with only those left. `/cgc reload` clears the marks. |
| `allow-empty-chests` | `true` | Golems may use an empty chest as a last resort. `false`: only labelled chests, chests already holding the item or its group, and `[Overflow]` chests. |
| `overflow-before-empty` | `true` | When no matching chest has room, try `[Overflow]` chests before empty ones. `false` fills empty chests first. |
| `item-frame-labels` | `true` | An item in a frame on a chest means "only this item goes here". |
| `sign-labels` | `true` | Sign labels (`[logs]`, `[Overflow]`, `[No Golems]`, `[Only ...]`). |
| `tidy-chests` | `true` | Merge partial stacks and keep like items together in the chests golems use. Copper chests and chests a player has open are left alone. |
| `destination-containers` | `[chest, trapped_chest]` | What golems put items into. Add `barrel` and/or `shulker_box` (every colour). Leave `chest` out to keep golems away from plain chests. Golems always take from copper chests. |
| `groups` | 50 groups | The item groups (see [How it works](#how-it-works)). Order matters for tidying: like items are ordered by the first group they're in. |

### Busier golems

| Setting | Default | What it does |
|---|---|---|
| `fetch-from-overflow` | `true` | A golem with nothing to do takes items that now have a home out of an `[Overflow]` chest and sorts them. |
| `fetchers-per-overflow-chest` | `2` | How many golems may fetch from one overflow chest at a time (each a different item). |
| `carry-amount` | `16` | Items a golem carries per trip, 1 to 64 (vanilla: 16). Items that stack to less (ender pearls 16, tools 1) are carried that many at most. |
| `deliver-reach` | `2` | How many chest rows up golems put items in, 2 to 6. Above row 2 the golem walks to the foot of the stack and the plugin moves the items. |
| `take-reach` | `2` | How many rows up golems take from copper chests and fetch from `[Overflow]` chests, 2 to 6. |

### Looking after golems

| Setting | Default | What it does |
|---|---|---|
| `golems-give-way` | `true` | A golem stuck nose to nose with another steps aside (backing up in a one-wide corridor). Empty hands give way to full ones. |
| `avoid-trapdoors` | `true` | Golems never walk onto or through trapdoors (vanilla golems fall through open ones). |
| `avoid-portals` | `true` | Golems never go through Nether portals, End portals or End gateways, unless on a lead or riding something. |
| `rescue-lost-golems` | `true` | A golem far from any copper chest (40 blocks from its own, or 12 up or down) walks back; one that can't, or is stuck where it can't reach a copper chest, triggers an alert. |
| `lost-golem-teleport-home` | `false` | Off by default. When on, a stuck golem is teleported back to its copper chest, but only if the owner of the claim that chest is in has `coppergolemclerk.teleport` (nobody does unless you grant it; checked through LuckPerms when the owner is offline). Golems whose chest isn't in a claim are never teleported. |
| `greet-other-golems` | `true` | An idle copper golem walks over to say hello to an iron or snow golem within 16 blocks. |
| `greet-cooldown-minutes` | `5` | How long before a golem greets again. |
| `max-golems-per-claim` | `0` | How many copper golems in one GriefPrevention claim use this plugin (0: no limit). The oldest keep it; the newest over the limit sort like vanilla, and the claim owner is told. Vanilla golems cost the same tick time (see [Golem limits](#golem-limits)). |
| `max-golems-outside-claims` | `0` | The same for unclaimed land, per world (without GriefPrevention: every golem in the world). |
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
| `find-highlight-count` | `5` | How many of the containers `/cgc find` lists glow for the player, nearest first, 0 to 30 (0: no glow). |
| `find-highlight-seconds` | `10` | How long the containers `/cgc find` lists glow for the player, 0 to 120 (0: no glow). |
| `golems-command-for-players` | `false` | Let players use `/cgc golems` to list their own golems (in claims they own, or whose copper chest is in one). Off: admins only. |

### Server

| Setting | Default | What it does |
|---|---|---|
| `disabled-worlds` | `[]` | Worlds (by folder name) where the plugin does nothing and golems behave as in vanilla, e.g. `[world_nether, world_the_end]`. |
| `update-check` | `true` | Check GitHub weekly for a newer release; log it and tell admins on join. Nothing is downloaded. |
| `debug` | `false` | Log every sorting decision. Useful while setting up groups and labels. |
| `trace-golems` | `false` | Log each golem's state, position and held item whenever they change. Very chatty. |

## Permissions

| Permission | Default | Allows |
|---|---|---|
| `coppergolemclerk.find` | everyone | `/cgc find`: containers in your own claims (wider with `find-outside-own-claims`). |
| `coppergolemclerk.check` | everyone | `/cgc check` on containers you could open, or the item in your hand. |
| `coppergolemclerk.overflow` | everyone | `/cgc overflow`: the `[Overflow]` chests near you that you could open. |
| `coppergolemclerk.stats` | everyone | `/cgc stats`: what golems sorted in your claims. |
| `coppergolemclerk.golems` | everyone | `/cgc golems`: your own golems. Only works if `golems-command-for-players` is `true` (it's `false` by default). |
| `coppergolemclerk.toggle` | everyone | Switching golems between the plugin and vanilla with the toggle item (with GriefPrevention, only where you may build). |
| `coppergolemclerk.allclaims` | op | `find`, `check`, `overflow`, `stats` and `golems` see into every claim and locked containers. |
| `coppergolemclerk.alerts` | op | Alerts (full chests, lost golems) usually go to the owner of the claim where they happen. Outside every claim there's no owner, so they go to online players with this permission instead (without GriefPrevention, every alert does). |
| `coppergolemclerk.teleport` | nobody | Stuck golems whose copper chest is in this player's claim may be teleported home, if `lost-golem-teleport-home` is `true`. |
| `coppergolemclerk.admin` | op | Every `/cgc` command (also `reload`, `version`, positions and `golems all`), and being told about new releases. |

## Building from source

Needs Java 25 (the Gradle wrapper downloads Gradle itself).

```
git clone https://github.com/mhardin18/copperGolemClerk.git
cd copperGolemClerk
./gradlew build
```

The jar is written to `build/libs/CopperGolemClerk-<version>.jar`. Install it as
described in [Install](#install).

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for what changed in each version.

## License

GPL-3.0. See [LICENSE](LICENSE).
