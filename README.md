# smartGolems (SmartCopperGolems)

A Paper plugin (Paper 26.3, Java 25) that makes copper golems sort *similar* items
together, not just identical ones.

Vanilla golems only put an item into a chest that already holds that exact item,
or into an empty chest. With this plugin, a golem carrying birch logs will also
use a chest that holds oak logs. A golem carrying a diamond pickaxe will use a
chest of tools, and so on.

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

Settings:

- `match-mode: all` (default): every item in the chest must share a group with the
  carried item. A stray item in a chest stops group matching, which keeps
  chests tidy. `any`: one item from the group is enough.
- `prefer-matching-chests: true` (default): see above.
- `matching-chest-timeout-seconds: 30`: when to give up on a matching chest.
- `allow-empty-chests: true` (default): golems may use empty chests as a last
  resort. Set `false` so golems only fill chests you've labelled.
- `debug: true`: logs each sort decision to the console.

## Commands

Requires `smartgolems.admin` (op by default). Alias `/sg`.

- `/smartgolems reload`: reload `config.yml`
- `/smartgolems check`: show which groups the item in your hand belongs to

## Build

```
./gradlew build
```

The jar is written to `build/libs/SmartCopperGolems-<version>.jar`. Copy it into
your server's `plugins/` folder and restart.

## License

GPL-3.0. See [LICENSE](LICENSE).
