# Sorting board tools

The [Golem Sorting Board](https://claude.ai/artifact/2saszixj5PGgx7Q5bUfA23) groups every
survival item into categories; `config.yml`'s `groups:` section is generated from it.

Inputs (not committed, regenerate when Minecraft updates):
- Item reports: `java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports --output <dir>/datagen/out`
- Item tags: unzip `data/minecraft/tags/item/*` from the vanilla server jar into `<dir>/tags`

Files:
- `classify.py <dir>`: the starting rules that sorted all survival items into themes.
- `board.template.html`: the board page; `/*ITEMS*/[]` is replaced with the item list.
- `to_config.py <dir> <layout.json> <out.yml>`: turns the board's saved layout (db doc
  `board/layout`) into compact groups using descriptive tags, name patterns and item names,
  and checks every item lands in exactly one group.
