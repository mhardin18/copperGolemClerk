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

Settings:

- `match-mode: all` (default): every item in the chest must share a group with the
  carried item. A stray item in a chest stops group matching, which keeps
  chests tidy. `any`: one item from the group is enough.
- `allow-empty-chests: true` (default): vanilla behaviour. Set `false` so golems
  only fill chests you've "labelled" by putting one item in them.
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
