# Sorting board tools

The [Golem Sorting Board](https://claude.ai/artifact/2saszixj5PGgx7Q5bUfA23) groups every
survival item into categories. The `groups:` section of `config.yml` is generated from it.

## Inputs

These are not committed. Regenerate them when Minecraft updates.

- Item reports: `java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports --output <dir>/datagen/out`
- Item tags: unzip `data/minecraft/tags/item/*` from the vanilla server jar into `<dir>/tags`

## Files

- `classify.py <dir>`: the starting rules that sorted all survival items into themes.
- `board.template.html`: the board page. `/*ITEMS*/[]` is replaced with the item list.
- `to_config.py <dir> <layout.json> <out.yml>`: turns the board's saved layout (db doc
  `board/layout`) into compact groups. It uses descriptive tags, name patterns and item
  names, and checks that every item lands in exactly one group.

## Group names

Group names are the sign labels players write. `to_config.py` fails if a name is too wide
for one sign line (90 pixels, measured the way `ChestReports.fitsOnSign` does). `NAMES`
maps long board ids to short names.

In 3.1.0 the board's single "Concrete & terracotta" category was split in two (`concrete`,
`terracotta`) in `classify.py` and in `config.yml`. A board layout saved before then still
has the one category. Split it on the board before regenerating, or `to_config.py`
reports the long name.
