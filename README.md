# smartCopperGolems (SmartCopperGolems)

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

1. Download `SmartCopperGolems-<version>.jar` from the
   [latest release](https://github.com/mhardin18/smartCopperGolems/releases/latest).
   Release tags name the Paper version they're for (e.g. `v1.0.0-paper26.3`).
2. Put the jar in your server's `plugins/` folder.
3. Restart the server (don't use `/reload`).
4. Check the console for `Enabling SmartCopperGolems`, or run `/plugins` in game.

On first start the plugin creates `plugins/SmartCopperGolems/config.yml`. The
defaults work as-is: about 50 groups that cover every survival item. After editing
the file, run `/sg reload` to apply it without a restart.

To update, replace the old jar with the new one and restart. Your `config.yml` is
kept. To uninstall, delete the jar (and the `plugins/SmartCopperGolems/` folder if
you don't want to keep the config).

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

Put a wall sign on any side of a chest (or a standing sign on top) with rules
in square brackets, one per line:

| Line        | Accepts                         |
|-------------|---------------------------------|
| `[logs]`    | the `logs` group from the config |
| `[oak log]` | that one item                    |
| `[*_ORE]`   | items matching the pattern       |
| `[#planks]` | items in that tag                |
| `[Overflow]`| anything with no other home      |

Case doesn't matter and spaces count as underscores. A group name wins over an
item with the same name, and group names work singular or plural (`[Rail]` =
`rails`, `[ore]` = `ores`, `[light source]` = `light-sources`). If a word matches
no group and no item, it matches items with that word in their name (`[carpet]`
= every carpet). For one exact item that shares a group's name, use a framed
item instead.

When several chests would take an item, golems use the most specific one. Labels
(frames and signs) come before contents: a framed item first, then the sign label
covering the fewest items, then a chest already holding that exact item, then a
group match by contents. A stew goes to `[stews]` before `[food]`, and a `[logs]`
chest wins over an unlabelled chest that happens to hold birch logs. Frame and sign labels on one chest combine.

Where a golem takes an item, in order: a matching chest with room (label, same
item or group), then an `[Overflow]` chest, then an empty chest
(`overflow-before-empty: false` swaps the last two). Overflow chests never count
as a match for what's already in them.

### Items with no home

If a golem can't deliver its item it works through four steps, each lasting
`matching-chest-timeout-seconds` (default 30): steer to the most specific matching
chest, overflow or empty chest; then any matching chest (the most specific one may be
unreachable); then only overflow or empty chests; then plain vanilla wandering. After
that, the item is put back into the copper chest it came from (golems can't walk to
copper chests while carrying, so it's moved directly) and marked "no home" there for
`homeless-retry-minutes` (default 5). Golems take other items first and skip a copper
chest that has only "no home" items left. Add a home for the item, then
`/smartcoppergolems reload` to have golems try again right away. Steps count only time the
golem is likely active (walking, or a player within the server's entity activation
range), so golems that Paper pauses far from players don't run out of time, and a
golem waiting its turn next to a chest that takes its item is never sent back.

### Re-sorting the overflow chest

With `fetch-from-overflow` (on by default), a golem with nothing to do (empty
hand, nothing to take from any copper chest in range) walks to an `[Overflow]`
chest and takes up to 16 of an item that now has a home, then sorts it. Up to
`fetchers-per-overflow-chest` golems (default 2) fetch from one overflow chest
at a time, each a different item, so they don't crowd it. So when
you add a chest for something that was piling up in overflow, golems move it
over on their own. Items with no home are left in overflow. Golems also no
longer walk to empty copper chests.

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
- `debug: true`: logs each sort decision to the console.

## Commands

Requires `smartcoppergolems.admin` (op by default). Alias `/sg`.

- `/smartcoppergolems reload`: reload `config.yml`
- `/smartcoppergolems check`: show which groups the item in your hand belongs to

## Building from source

Needs Java 25 (the Gradle wrapper downloads Gradle itself).

```
git clone https://github.com/mhardin18/smartCopperGolems.git
cd smartCopperGolems
./gradlew build
```

The jar is written to `build/libs/SmartCopperGolems-<version>.jar`. Install it as
described in [Install](#install).

## License

GPL-3.0. See [LICENSE](LICENSE).
