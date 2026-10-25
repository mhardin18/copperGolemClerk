# Changelog

All versions are for **Paper 26.3** (Java 25). To upgrade, replace the old `SmartCopperGolems-*.jar` in `plugins/` with the new one and restart. Your `config.yml` is kept, and new settings use their defaults until you add them.

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
