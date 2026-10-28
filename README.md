# Copper Golem Clerk

A Paper plugin (Paper 26.3, Java 25) that makes copper golems sort *similar* items
together, not just identical ones.

Vanilla golems only put an item into a chest that already holds that exact item,
or into an empty chest. With this plugin, a golem carrying birch logs will also
use a chest that holds oak logs. A golem carrying a diamond pickaxe will use a
chest of tools, and so on.

## Requirements

- A [Paper](https://papermc.io/downloads/paper) server, version **26.3**
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
defaults work as-is: about 50 groups that cover every survival item. After editing
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

Paper fires `ItemTransportingEntitySortEvent` whenever a golem checks whether
its item belongs in a chest. The plugin answers "yes" when the chest's items
share a **group** with the carried item. Otherwise vanilla rules apply.

Groups are defined in `config.yml` using:

| Entry           | Meaning                                    |
|-----------------|--------------------------------------------|
| `DIAMOND`       | one item                                   |
| `*_ORE`         | name pattern                               |
| `#minecraft:logs` | vanilla or datapack item tag             |
| `@edible`, `@fuel`, `@record` | item properties              |

### Smarter use of empty chests

Vanilla golems walk to the nearest chest they haven't tried and drop their item
into the first empty chest they reach, often before finding the chest where it
belongs. They also give up after trying 10 chests, so far-away chests never get
used.

With `prefer-matching-chests` (on by default), a golem carrying an item first
looks over every chest in its range: 32 blocks sideways and 8 up or down, in
loaded chunks. If any chest matches the item (frame label, same item, or same
group) and has room, the golem walks straight to it. Empty chests are used only
when no matching chest has room. If the golem hasn't delivered within
`matching-chest-timeout-seconds` (default 30), for example because the chest is
walled off, it falls back to vanilla behavior for that item.

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
that, the item is put back into the copper chest it came from (golems can't walk to
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
chest and takes up to 16 of an item that now has a home, then sorts it. Up to
`fetchers-per-overflow-chest` golems (default 2) fetch from one overflow chest
at a time, each a different item, so they don't crowd it. So when
you add a chest for something that was piling up in overflow, golems move it
over on their own. Items with no home are left in overflow, and so are items in
an overflow chest golems can't open (locked, blocked or waterlogged). Golems also no
longer walk to empty copper chests.

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
- A golem never steps aside into fire, lava, water, a cobweb, powder snow, a
  berry bush or a trapdoor, or onto a campfire, magma block or trapdoor; it backs up
  to a safe spot instead.

### Golems keep off trapdoors

Vanilla golems treat an open trapdoor as floor, so a trapdoor over a hole (a ladder
shaft, a drop into a farm) catches them: they walk onto it and fall in. With
`avoid-trapdoors` (on by default), golems plan their paths around trapdoors, open or
closed, and never stand on one. A golem whose only way to a chest is across a
trapdoor won't reach it, and its item goes elsewhere as usual.

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

Settings:

- `match-mode: all` (default): every item in the chest must share a group with the
  carried item. A stray item in a chest stops group matching, which keeps
  chests tidy. `any`: one item from the group is enough.
- `prefer-matching-chests: true` (default): see above.
- `matching-chest-timeout-seconds: 30`: length of each step above.
- `return-unsortable-items: true`, `homeless-retry-minutes: 5`: see above.
- `fetch-from-overflow: true`, `fetchers-per-overflow-chest: 2`: see above.
- `sign-labels: true` (default): sign labels and `[Overflow]` chests.
- `overflow-before-empty: true` (default): see above.
- `allow-empty-chests: true` (default): golems may use empty chests as a last
  resort. Set `false` so golems only fill chests you've labelled.
- `golems-give-way: true` (default): see above.
- `avoid-trapdoors: true` (default): see above.
- `tidy-chests: true` (default): see above.
- `disabled-worlds: []`: worlds where the plugin does nothing and golems behave as in
  vanilla, e.g. `[world_nether, world_the_end]`.
- `debug: true`: logs each sort decision to the console.

## Commands

Requires `coppergolemclerk.admin` (op by default). Alias `/cgc`.

- `/cgc help`: list the commands. `/cgc help <command>` explains one.
- `/cgc reload`: reload `config.yml`.
- `/cgc check` while looking at a chest: what each label on it means (`[Horse]` =
  items with "horse" in their name, 9 items: ...), labels that match nothing, what
  the chest takes, whether it takes the item in your hand, and anything stopping
  golems from using it (a block on top, a lock, a hopper feeding a copper chest, full).
- `/cgc check` while not looking at a chest: which groups the item in your hand belongs to.
- `/cgc overflow`: what's in the `[Overflow]` chests within golem range of you, split into
  items with no chest (with a sign label to use for each group), items whose chest is
  full, and items golems will move on their own.

From the console, give a position: `/cgc check <x> <y> <z> [world]`,
`/cgc overflow <x> <y> <z> [world]`.

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
