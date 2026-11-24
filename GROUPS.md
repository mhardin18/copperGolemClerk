# Item groups

Item groups are how golems know that birch logs belong with oak logs. Each group is a list in
the `groups:` section of `config.yml`. A golem puts an item in a chest whose sign names one of
the item's groups, or a chest holding items from the same group. The README explains
[how golems use them](README.md#how-it-works) and
[what you can write in a group](README.md#-patterns--tags-and--properties).

This file covers:

- [What upgrading does to your groups](#what-upgrading-does-to-your-groups): your groups
  are never changed.
- [Getting the newer built-in groups](#getting-the-newer-built-in-groups), if you want them.
- [Changes to the built-in groups](#changes-to-the-built-in-groups), release by release.
- [The built-in groups in this release](#the-built-in-groups-in-this-release): every group,
  its entries and every item in it.

This copy is for **3.3.0**. Each release's copy is in its source on GitHub, under that
release's tag (for example `v3.2.0-paper26.3`).

## What upgrading does to your groups

**Installing a new version never changes your groups.** This is checked by the plugin's tests
on every build. A fresh `config.yml` is written only when there is none. After that, the file
is yours. When a new version starts on an existing `config.yml`, it:

- **keeps every line of `groups:` and `group-aliases:` as you wrote it**: your own groups,
  groups you edited or deleted, entries, comments, blank lines and commented-out lines;
- **doesn't add the new built-in groups**, or new items in a built-in group, to your file;
- **replaces the explanation right above `groups:`** (and above `group-aliases:` and every
  other setting) with this version's. That is the block of `#` lines at the left margin
  directly above the `groups:` line. Comments you put inside the section (indented, or at the
  left margin between groups) are kept. Put your own notes inside the section, not right above
  `groups:`;
- **adds `group-aliases: {}`** if your file doesn't have it (it's new in 3.2.0), and the whole
  built-in `groups:` list only if your file has no `groups:` at all;
- **saves the old file first**, as `plugins/CopperGolemClerk/config-before-<version>.yml`,
  and says in the console what it changed.

**Renamed groups keep working.** When a built-in group is renamed, signs work with either
name, whichever your file has. For example, `[foliage]` finds an older file's
`saplings-and-leaves`. Signs aren't changed either.

**What this means for you:**

- If you set up your own groups, an upgrade doesn't touch them.
- If you kept the built-in groups, you keep the ones from the version you first installed,
  until you copy in newer ones yourself.
- If your groups seem out of date, run `/cgc groups`. It compares them with this version's
  built-in groups.

## Getting the newer built-in groups

Run `/cgc groups` (admins and the console). It shows:

- any problems with your groups;
- items you put in more than one group;
- built-in groups you don't have;
- built-in groups whose items differ from yours.

Then:

- `/cgc groups <group>` shows one group's entries and how they differ from the built-in
  ones.
- `/cgc groups item <item>` shows which groups an item is in.

To take a built-in group, copy it from [the list below](#the-built-in-groups-in-this-release),
or from this version's `config.yml`, into your `groups:` section. Then run `/cgc reload`.

To start over with every built-in group, delete the `groups:` section, or the whole
`config.yml`, and restart the server. Deleting the whole file resets every setting, not only
the groups.

## Changes to the built-in groups

Only a fresh `config.yml` gets these. See
[What upgrading does to your groups](#what-upgrading-does-to-your-groups).

### 3.3.0: 57 groups

- New `resin` group with all 8 resin items: the resin clump, resin brick, resin block,
  resin bricks (chiseled too) and resin brick slab, stairs and wall. The resin brick item
  moved there from `materials`; the blocks stay in `building` and the clump in `mob-drops`
  as well, but golems prefer `[resin]` (it has the fewest items).
- The nether brick item moved from `materials` to `nether`, next to the nether brick
  blocks. Before, golems took it to a `[materials]` chest and never to a `[nether]` one.

### 3.2.0: 56 groups

- `decorations` is split into `signs`, `banners` (with banner patterns), `pottery` (sherds and
  the decorated pot) and `beds`. `decorations` keeps shelves, cushions, copper golem statues,
  item frames, the painting, armor stand, bookshelves and the flower pot.
- Mob heads and skulls moved to `rare-items`.
- `utility-blocks` is merged into `workstations`. `[utility blocks]` signs find
  `workstations`.
- Cherry leaves, flowering azalea leaves and the mangrove propagule moved from `flowers` to
  `foliage`. The chorus flower moved from `flowers` to `end`.
- The name tag moved from `tools` to `animal-gear`.
- `stone` (81 items) is split into `stone` (47), `deepslate` (20) and `tuff` (14), so each
  fits in a double chest. A `[deepslate]` sign now means the deepslate group, without the
  deepslate ores.
- Creative-only items are in no group: budding amethyst, infested cobblestone and mossy stone
  bricks, the command block minecart, the knowledge book, and the copper, iron and snow golem
  spawn eggs.

### 3.1.0: 51 groups

Four groups have shorter names, so their sign labels fit on one line:

- `smithing-templates` is now `templates`.
- `crafting-materials` is now `materials`.
- `saplings-and-leaves` is now `foliage`.
- `concrete-and-terracotta` is split into `concrete` (blocks, powder, slabs and stairs) and
  `terracotta` (plain, dyed and glazed).

Old and new names both work on signs.

### 1.0.0 to 3.0.0: 50 groups

The first built-in groups, unchanged through 3.0.0.

## The built-in groups in this release

These are the groups in a fresh 3.3.0 `config.yml`, with every item each one has on
Paper 26.3. An item can be in more than one group; golems prefer the group with the fewest
items. "Entries" are the lines in `config.yml`: item names, `*` patterns, `#` tags and `@`
properties.

<!-- built-in groups: made by GroupsDocPaperTest, don't edit by hand -->

| Sign label | Group in `config.yml` | What's in it | Items |
|---|---|---|---|
| [`[music discs]`](#music-discs) | `music-discs` | Music discs | 23 |
| [`[templates]`](#templates) | `templates` | Smithing templates | 19 |
| [`[rare items]`](#rare-items) | `rare-items` | Rare & trophies | 25 |
| [`[buckets]`](#buckets) | `buckets` | Buckets | 12 |
| [`[brewing]`](#brewing) | `brewing` | Potions & brewing | 9 |
| [`[books]`](#books) | `books` | Books & enchanting | 5 |
| [`[maps]`](#maps) | `maps` | Maps & navigation | 23 |
| [`[fireworks]`](#fireworks) | `fireworks` | Fireworks | 2 |
| [`[food]`](#food) | `food` | Food | 38 |
| [`[seeds]`](#seeds) | `seeds` | Seeds | 7 |
| [`[farming]`](#farming) | `farming` | Crops & farming | 14 |
| [`[mob drops]`](#mob-drops) | `mob-drops` | Mob drops | 27 |
| [`[ores]`](#ores) | `ores` | Ores | 22 |
| [`[mineral blocks]`](#mineral-blocks) | `mineral-blocks` | Mineral blocks | 8 |
| [`[ingots and gems]`](#ingots-and-gems) | `ingots-and-gems` | Ingots & gems | 19 |
| [`[materials]`](#materials) | `materials` | Crafting materials | 7 |
| [`[amethyst]`](#amethyst) | `amethyst` | Amethyst | 6 |
| [`[animal gear]`](#animal-gear) | `animal-gear` | Saddles & animal armor | 31 |
| [`[armor]`](#armor) | `armor` | Armor | 31 |
| [`[weapons]`](#weapons) | `weapons` | Weapons & ammo | 23 |
| [`[tools]`](#tools) | `tools` | Tools | 34 |
| [`[boats]`](#boats) | `boats` | Boats | 22 |
| [`[rails]`](#rails) | `rails` | Rails & minecarts | 9 |
| [`[redstone]`](#redstone) | `redstone` | Redstone | 72 |
| [`[light sources]`](#light-sources) | `light-sources` | Light sources | 40 |
| [`[storage]`](#storage) | `storage` | Storage | 45 |
| [`[workstations]`](#workstations) | `workstations` | Workstations | 22 |
| [`[signs]`](#signs) | `signs` | Signs | 26 |
| [`[banners]`](#banners) | `banners` | Banners & patterns | 26 |
| [`[pottery]`](#pottery) | `pottery` | Pottery sherds & decorated pot | 24 |
| [`[beds]`](#beds) | `beds` | Beds | 17 |
| [`[decorations]`](#decorations) | `decorations` | Decorations | 44 |
| [`[wool]`](#wool) | `wool` | Wool & carpet | 64 |
| [`[concrete]`](#concrete) | `concrete` | Concrete: blocks, powder, slabs & stairs | 64 |
| [`[terracotta]`](#terracotta) | `terracotta` | Terracotta: plain, dyed & glazed | 33 |
| [`[glass]`](#glass) | `glass` | Glass | 35 |
| [`[dyes]`](#dyes) | `dyes` | Dyes | 16 |
| [`[mushrooms]`](#mushrooms) | `mushrooms` | Mushrooms | 6 |
| [`[flowers]`](#flowers) | `flowers` | Flowers | 27 |
| [`[foliage]`](#foliage) | `foliage` | Saplings & leaves | 26 |
| [`[sculk]`](#sculk) | `sculk` | Sculk | 4 |
| [`[sulfur]`](#sulfur) | `sulfur` | Sulfur & cinnabar | 28 |
| [`[ocean]`](#ocean) | `ocean` | Ocean | 45 |
| [`[nether]`](#nether) | `nether` | Nether | 45 |
| [`[end]`](#end) | `end` | The End | 11 |
| [`[copper]`](#copper) | `copper` | Copper blocks | 80 |
| [`[logs]`](#logs) | `logs` | Logs & planks | 64 |
| [`[wooden building]`](#wooden-building) | `wooden-building` | Wooden building | 80 |
| [`[dirt and sand]`](#dirt-and-sand) | `dirt-and-sand` | Dirt, sand & gravel | 12 |
| [`[ice and snow]`](#ice-and-snow) | `ice-and-snow` | Ice & snow | 6 |
| [`[plants]`](#plants) | `plants` | Plants | 26 |
| [`[stone]`](#stone) | `stone` | Stone | 47 |
| [`[deepslate]`](#deepslate) | `deepslate` | Deepslate | 20 |
| [`[tuff]`](#tuff) | `tuff` | Tuff | 14 |
| [`[sandstone]`](#sandstone) | `sandstone` | Sandstone | 20 |
| [`[resin]`](#resin) | `resin` | Resin | 8 |
| [`[building]`](#building) | `building` | Bricks, quartz & other building | 29 |

### music-discs

`[music discs]`: Music discs, 23 items.

Entries: `*DISC*`

<details><summary>Items</summary>

disc fragment 5, music disc 5, music disc 11, music disc 13, music disc blocks, music disc bounce, music disc cat, music disc chirp, music disc creator, music disc creator music box, music disc far, music disc lava chicken, music disc mall, music disc mellohi, music disc otherside, music disc pigstep, music disc precipice, music disc relic, music disc stal, music disc strad, music disc tears, music disc wait, music disc ward

</details>

### templates

`[templates]`: Smithing templates, 19 items.

Entries: `*TEMPLATE*`

<details><summary>Items</summary>

bolt armor trim smithing template, coast armor trim smithing template, dune armor trim smithing template, eye armor trim smithing template, flow armor trim smithing template, host armor trim smithing template, netherite upgrade smithing template, raiser armor trim smithing template, rib armor trim smithing template, sentry armor trim smithing template, shaper armor trim smithing template, silence armor trim smithing template, snout armor trim smithing template, spire armor trim smithing template, tide armor trim smithing template, vex armor trim smithing template, ward armor trim smithing template, wayfinder armor trim smithing template, wild armor trim smithing template

</details>

### rare-items

`[rare items]`: Rare & trophies, 25 items.

Entries: `BEACON`, `CONDUIT`, `CREAKING_HEART`, `CREEPER_HEAD`, `DRAGON_EGG`, `DRAGON_HEAD`, `DRIED_GHAST`, `ECHO_SHARD`, `ENCHANTED_GOLDEN_APPLE`, `END_CRYSTAL`, `GOAT_HORN`, `HEART_OF_THE_SEA`, `HEAVY_CORE`, `NETHER_STAR`, `OMINOUS_BOTTLE`, `OMINOUS_TRIAL_KEY`, `PIGLIN_HEAD`, `PLAYER_HEAD`, `SKELETON_SKULL`, `SNIFFER_EGG`, `TOTEM_OF_UNDYING`, `TRIAL_KEY`, `TURTLE_EGG`, `WITHER_SKELETON_SKULL`, `ZOMBIE_HEAD`

<details><summary>Items</summary>

echo shard, enchanted golden apple, end crystal, goat horn, heart of the sea, nether star, ominous bottle, ominous trial key, totem of undying, trial key, beacon, conduit, creaking heart, creeper head, dragon egg, dragon head, dried ghast, heavy core, piglin head, player head, skeleton skull, sniffer egg, turtle egg, wither skeleton skull, zombie head

</details>

### buckets

`[buckets]`: Buckets, 12 items.

Entries: `*BUCKET*`

<details><summary>Items</summary>

axolotl bucket, bucket, cod bucket, lava bucket, milk bucket, powder snow bucket, pufferfish bucket, salmon bucket, sulfur cube bucket, tadpole bucket, tropical fish bucket, water bucket

</details>

### brewing

`[brewing]`: Potions & brewing, 9 items.

Entries: `*POTION*`, `BLAZE_POWDER`, `DRAGON_BREATH`, `FERMENTED_SPIDER_EYE`, `GLASS_BOTTLE`, `GLISTERING_MELON_SLICE`, `NETHER_WART`

<details><summary>Items</summary>

blaze powder, dragon breath, fermented spider eye, glass bottle, glistering melon slice, lingering potion, potion, splash potion, nether wart

</details>

### books

`[books]`: Books & enchanting, 5 items.

Entries: `BOOK`, `ENCHANTED_BOOK`, `WRITABLE_BOOK`, `WRITTEN_BOOK`, `EXPERIENCE_BOTTLE`

<details><summary>Items</summary>

book, enchanted book, experience bottle, writable book, written book

</details>

### maps

`[maps]`: Maps & navigation, 23 items.

Entries: `*_MAP`, `CLOCK`, `COMPASS`, `LODESTONE`, `MAP`, `RECOVERY_COMPASS`, `SPYGLASS`

<details><summary>Items</summary>

abandoned camp map, buried ancient city map, buried mineshaft map, buried treasure map, buried trial chambers map, clock, compass, desert pyramid map, desert village map, filled map, jungle pyramid map, map, ocean monument map, plains village map, recovery compass, savanna village map, snowy village map, spyglass, swamp hut map, taiga village map, warm ocean ruins map, woodland mansion map, lodestone

</details>

### fireworks

`[fireworks]`: Fireworks, 2 items.

Entries: `FIREWORK_ROCKET`, `FIREWORK_STAR`

<details><summary>Items</summary>

firework rocket, firework star

</details>

### food

`[food]`: Food, 38 items.

Entries: `#minecraft:fishes`, `*STEW*`, `*COOKED*`, `*POTATO*`, `APPLE`, `BEEF`, `BEETROOT`, `BEETROOT_SOUP`, `BREAD`, `CAKE`, `CARROT`, `CHICKEN`, `CHORUS_FRUIT`, `COOKIE`, `DRIED_KELP`, `GLOW_BERRIES`, `GOLDEN_APPLE`, `GOLDEN_CARROT`, `HONEY_BOTTLE`, `MELON_SLICE`, `MUTTON`, `PORKCHOP`, `PUMPKIN_PIE`, `RABBIT`, `SWEET_BERRIES`

<details><summary>Items</summary>

apple, baked potato, beef, beetroot, beetroot soup, bread, carrot, chicken, chorus fruit, cod, cooked beef, cooked chicken, cooked cod, cooked mutton, cooked porkchop, cooked rabbit, cooked salmon, cookie, dried kelp, glow berries, golden apple, golden carrot, honey bottle, melon slice, mushroom stew, mutton, poisonous potato, porkchop, potato, pufferfish, pumpkin pie, rabbit, rabbit stew, salmon, suspicious stew, sweet berries, tropical fish, cake

</details>

### seeds

`[seeds]`: Seeds, 7 items.

Entries: `*SEEDS*`, `COCOA_BEANS`, `PITCHER_POD`

<details><summary>Items</summary>

beetroot seeds, cocoa beans, melon seeds, pitcher pod, pumpkin seeds, torchflower seeds, wheat seeds

</details>

### farming

`[farming]`: Crops & farming, 14 items.

Entries: `BEEHIVE`, `BEE_NEST`, `BONE_BLOCK`, `BONE_MEAL`, `CARVED_PUMPKIN`, `DRIED_KELP_BLOCK`, `HAY_BLOCK`, `HONEYCOMB`, `HONEYCOMB_BLOCK`, `MELON`, `PUMPKIN`, `SUGAR`, `SUGAR_CANE`, `WHEAT`

<details><summary>Items</summary>

bone meal, honeycomb, sugar, bee nest, beehive, bone block, carved pumpkin, dried kelp block, hay block, honeycomb block, melon, pumpkin, sugar cane, wheat

</details>

### mob-drops

`[mob drops]`: Mob drops, 27 items.

Entries: `ARMADILLO_SCUTE`, `BLAZE_ROD`, `BLUE_EGG`, `BONE`, `BREEZE_ROD`, `BROWN_EGG`, `EGG`, `ENDER_EYE`, `ENDER_PEARL`, `FEATHER`, `GHAST_TEAR`, `GLOW_INK_SAC`, `GUNPOWDER`, `INK_SAC`, `LEATHER`, `MAGMA_CREAM`, `NAUTILUS_SHELL`, `PHANTOM_MEMBRANE`, `RABBIT_FOOT`, `RABBIT_HIDE`, `RESIN_CLUMP`, `ROTTEN_FLESH`, `SHULKER_SHELL`, `SLIME_BALL`, `SPIDER_EYE`, `STRING`, `TURTLE_SCUTE`

<details><summary>Items</summary>

armadillo scute, blaze rod, blue egg, bone, breeze rod, brown egg, egg, ender eye, ender pearl, feather, ghast tear, glow ink sac, gunpowder, ink sac, leather, magma cream, nautilus shell, phantom membrane, rabbit foot, rabbit hide, rotten flesh, shulker shell, slime ball, spider eye, string, turtle scute, resin clump

</details>

### ores

`[ores]`: Ores, 22 items.

Entries: `*_ORE`, `ANCIENT_DEBRIS`, `RAW_COPPER_BLOCK`, `RAW_GOLD_BLOCK`, `RAW_IRON_BLOCK`

<details><summary>Items</summary>

ancient debris, coal ore, copper ore, deepslate coal ore, deepslate copper ore, deepslate diamond ore, deepslate emerald ore, deepslate gold ore, deepslate iron ore, deepslate lapis ore, deepslate redstone ore, diamond ore, emerald ore, gold ore, iron ore, lapis ore, nether gold ore, nether quartz ore, raw copper block, raw gold block, raw iron block, redstone ore

</details>

### mineral-blocks

`[mineral blocks]`: Mineral blocks, 8 items.

Entries: `COAL_BLOCK`, `DIAMOND_BLOCK`, `EMERALD_BLOCK`, `GOLD_BLOCK`, `IRON_BLOCK`, `LAPIS_BLOCK`, `NETHERITE_BLOCK`, `REDSTONE_BLOCK`

<details><summary>Items</summary>

coal block, diamond block, emerald block, gold block, iron block, lapis block, netherite block, redstone block

</details>

### ingots-and-gems

`[ingots and gems]`: Ingots & gems, 19 items.

Entries: `*INGOT*`, `*NUGGET*`, `CHARCOAL`, `COAL`, `DIAMOND`, `EMERALD`, `LAPIS_LAZULI`, `NETHERITE_SCRAP`, `PRISMARINE_CRYSTALS`, `PRISMARINE_SHARD`, `QUARTZ`, `RAW_COPPER`, `RAW_GOLD`, `RAW_IRON`

<details><summary>Items</summary>

charcoal, coal, copper ingot, copper nugget, diamond, emerald, gold ingot, gold nugget, iron ingot, iron nugget, lapis lazuli, netherite ingot, netherite scrap, prismarine crystals, prismarine shard, quartz, raw copper, raw gold, raw iron

</details>

### materials

`[materials]`: Crafting materials, 7 items.

Entries: `BOWL`, `BRICK`, `CLAY_BALL`, `FLINT`, `GLOWSTONE_DUST`, `PAPER`, `STICK`

<details><summary>Items</summary>

bowl, brick, clay ball, flint, glowstone dust, paper, stick

</details>

### amethyst

`[amethyst]`: Amethyst, 6 items.

Entries: `AMETHYST_*`, `*_AMETHYST_BUD`

<details><summary>Items</summary>

amethyst shard, amethyst block, amethyst cluster, large amethyst bud, medium amethyst bud, small amethyst bud

</details>

### animal-gear

`[animal gear]`: Saddles & animal armor, 31 items.

Entries: `#minecraft:harnesses`, `*_ARMOR`, `LEAD`, `NAME_TAG`, `SADDLE`

<details><summary>Items</summary>

black harness, blue harness, brown harness, copper horse armor, copper nautilus armor, cyan harness, diamond horse armor, diamond nautilus armor, golden horse armor, golden nautilus armor, gray harness, green harness, iron horse armor, iron nautilus armor, lead, leather horse armor, light blue harness, light gray harness, lime harness, magenta harness, name tag, netherite horse armor, netherite nautilus armor, orange harness, pink harness, purple harness, red harness, saddle, white harness, wolf armor, yellow harness

</details>

### armor

`[armor]`: Armor, 31 items.

Entries: `#minecraft:leg_armor`, `#minecraft:foot_armor`, `#minecraft:head_armor`, `#minecraft:chest_armor`, `ELYTRA`, `SHIELD`

<details><summary>Items</summary>

chainmail boots, chainmail chestplate, chainmail helmet, chainmail leggings, copper boots, copper chestplate, copper helmet, copper leggings, diamond boots, diamond chestplate, diamond helmet, diamond leggings, elytra, golden boots, golden chestplate, golden helmet, golden leggings, iron boots, iron chestplate, iron helmet, iron leggings, leather boots, leather chestplate, leather helmet, leather leggings, netherite boots, netherite chestplate, netherite helmet, netherite leggings, shield, turtle helmet

</details>

### weapons

`[weapons]`: Weapons & ammo, 23 items.

Entries: `#minecraft:arrows`, `#minecraft:spears`, `#minecraft:swords`, `BOW`, `CROSSBOW`, `FIRE_CHARGE`, `MACE`, `TRIDENT`, `WIND_CHARGE`

<details><summary>Items</summary>

arrow, bow, copper spear, copper sword, crossbow, diamond spear, diamond sword, fire charge, golden spear, golden sword, iron spear, iron sword, mace, netherite spear, netherite sword, spectral arrow, stone spear, stone sword, tipped arrow, trident, wind charge, wooden spear, wooden sword

</details>

### tools

`[tools]`: Tools, 34 items.

Entries: `#minecraft:axes`, `#minecraft:hoes`, `#minecraft:shovels`, `#minecraft:pickaxes`, `BRUSH`, `CARROT_ON_A_STICK`, `FISHING_ROD`, `FLINT_AND_STEEL`, `SHEARS`, `WARPED_FUNGUS_ON_A_STICK`

<details><summary>Items</summary>

brush, carrot on a stick, copper axe, copper hoe, copper pickaxe, copper shovel, diamond axe, diamond hoe, diamond pickaxe, diamond shovel, fishing rod, flint and steel, golden axe, golden hoe, golden pickaxe, golden shovel, iron axe, iron hoe, iron pickaxe, iron shovel, netherite axe, netherite hoe, netherite pickaxe, netherite shovel, shears, stone axe, stone hoe, stone pickaxe, stone shovel, warped fungus on a stick, wooden axe, wooden hoe, wooden pickaxe, wooden shovel

</details>

### boats

`[boats]`: Boats, 22 items.

Entries: `#minecraft:boats`

<details><summary>Items</summary>

acacia boat, acacia chest boat, bamboo chest raft, bamboo raft, birch boat, birch chest boat, cherry boat, cherry chest boat, dark oak boat, dark oak chest boat, jungle boat, jungle chest boat, mangrove boat, mangrove chest boat, oak boat, oak chest boat, pale oak boat, pale oak chest boat, poplar boat, poplar chest boat, spruce boat, spruce chest boat

</details>

### rails

`[rails]`: Rails & minecarts, 9 items.

Entries: `#minecraft:rails`, `CHEST_MINECART`, `FURNACE_MINECART`, `HOPPER_MINECART`, `MINECART`, `TNT_MINECART`

<details><summary>Items</summary>

chest minecart, furnace minecart, hopper minecart, minecart, tnt minecart, activator rail, detector rail, powered rail, rail

</details>

### redstone

`[redstone]`: Redstone, 72 items.

Entries: `#minecraft:buttons`, `*BULB*`, `*_PLATE`, `*LIGHTNING*`, `CALIBRATED_SCULK_SENSOR`, `COMPARATOR`, `CRAFTER`, `DAYLIGHT_DETECTOR`, `DISPENSER`, `DROPPER`, `HONEY_BLOCK`, `HOPPER`, `IRON_DOOR`, `IRON_TRAPDOOR`, `LEVER`, `NOTE_BLOCK`, `OBSERVER`, `PISTON`, `REDSTONE`, `REDSTONE_LAMP`, `REDSTONE_TORCH`, `REPEATER`, `SCULK_SENSOR`, `SLIME_BLOCK`, `STICKY_PISTON`, `TARGET`, `TNT`, `TRIPWIRE_HOOK`

<details><summary>Items</summary>

redstone, acacia button, acacia pressure plate, bamboo button, bamboo pressure plate, birch button, birch pressure plate, calibrated sculk sensor, cherry button, cherry pressure plate, comparator, copper bulb, crafter, crimson button, crimson pressure plate, dark oak button, dark oak pressure plate, daylight detector, dispenser, dropper, exposed copper bulb, exposed lightning rod, heavy weighted pressure plate, honey block, hopper, iron door, iron trapdoor, jungle button, jungle pressure plate, lever, light weighted pressure plate, lightning rod, mangrove button, mangrove pressure plate, note block, oak button, oak pressure plate, observer, oxidized copper bulb, oxidized lightning rod, pale oak button, pale oak pressure plate, piston, polished blackstone button, polished blackstone pressure plate, poplar button, poplar pressure plate, redstone lamp, redstone torch, repeater, sculk sensor, slime block, spruce button, spruce pressure plate, sticky piston, stone button, stone pressure plate, target, tnt, tripwire hook, warped button, warped pressure plate, waxed copper bulb, waxed exposed copper bulb, waxed exposed lightning rod, waxed lightning rod, waxed oxidized copper bulb, waxed oxidized lightning rod, waxed weathered copper bulb, waxed weathered lightning rod, weathered copper bulb, weathered lightning rod

</details>

### light-sources

`[light sources]`: Light sources, 40 items.

Entries: `#minecraft:candles`, `*LANTERN*`, `*FROGLIGHT*`, `CAMPFIRE`, `COPPER_TORCH`, `END_ROD`, `GLOWSTONE`, `SHROOMLIGHT`, `SOUL_CAMPFIRE`, `SOUL_TORCH`, `TORCH`

<details><summary>Items</summary>

black candle, blue candle, brown candle, campfire, candle, copper lantern, copper torch, cyan candle, end rod, exposed copper lantern, glowstone, gray candle, green candle, jack o lantern, lantern, light blue candle, light gray candle, lime candle, magenta candle, ochre froglight, orange candle, oxidized copper lantern, pearlescent froglight, pink candle, purple candle, red candle, sea lantern, shroomlight, soul campfire, soul lantern, soul torch, torch, verdant froglight, waxed copper lantern, waxed exposed copper lantern, waxed oxidized copper lantern, waxed weathered copper lantern, weathered copper lantern, white candle, yellow candle

</details>

### storage

`[storage]`: Storage, 45 items.

Entries: `#minecraft:bundles`, `#minecraft:shulker_boxes`, `*_CHEST`, `CHEST`

<details><summary>Items</summary>

black bundle, blue bundle, brown bundle, bundle, cyan bundle, gray bundle, green bundle, light blue bundle, light gray bundle, lime bundle, magenta bundle, orange bundle, pink bundle, purple bundle, red bundle, white bundle, yellow bundle, black shulker box, blue shulker box, brown shulker box, chest, copper chest, cyan shulker box, ender chest, exposed copper chest, gray shulker box, green shulker box, light blue shulker box, light gray shulker box, lime shulker box, magenta shulker box, orange shulker box, oxidized copper chest, pink shulker box, purple shulker box, red shulker box, shulker box, trapped chest, waxed copper chest, waxed exposed copper chest, waxed oxidized copper chest, waxed weathered copper chest, weathered copper chest, white shulker box, yellow shulker box

</details>

### workstations

`[workstations]`: Workstations, 22 items.

Entries: `*ANVIL*`, `BARREL`, `BELL`, `BLAST_FURNACE`, `BREWING_STAND`, `CARTOGRAPHY_TABLE`, `CAULDRON`, `COMPOSTER`, `CRAFTING_TABLE`, `ENCHANTING_TABLE`, `FLETCHING_TABLE`, `FURNACE`, `GRINDSTONE`, `JUKEBOX`, `LECTERN`, `LOOM`, `RESPAWN_ANCHOR`, `SMITHING_TABLE`, `SMOKER`, `STONECUTTER`

<details><summary>Items</summary>

anvil, barrel, bell, blast furnace, brewing stand, cartography table, cauldron, chipped anvil, composter, crafting table, damaged anvil, enchanting table, fletching table, furnace, grindstone, jukebox, lectern, loom, respawn anchor, smithing table, smoker, stonecutter

</details>

### signs

`[signs]`: Signs, 26 items.

Entries: `*_SIGN`

<details><summary>Items</summary>

acacia hanging sign, acacia sign, bamboo hanging sign, bamboo sign, birch hanging sign, birch sign, cherry hanging sign, cherry sign, crimson hanging sign, crimson sign, dark oak hanging sign, dark oak sign, jungle hanging sign, jungle sign, mangrove hanging sign, mangrove sign, oak hanging sign, oak sign, pale oak hanging sign, pale oak sign, poplar hanging sign, poplar sign, spruce hanging sign, spruce sign, warped hanging sign, warped sign

</details>

### banners

`[banners]`: Banners & patterns, 26 items.

Entries: `*BANNER*`

<details><summary>Items</summary>

bordure indented banner pattern, creeper banner pattern, field masoned banner pattern, flow banner pattern, flower banner pattern, globe banner pattern, guster banner pattern, mojang banner pattern, piglin banner pattern, skull banner pattern, black banner, blue banner, brown banner, cyan banner, gray banner, green banner, light blue banner, light gray banner, lime banner, magenta banner, orange banner, pink banner, purple banner, red banner, white banner, yellow banner

</details>

### pottery

`[pottery]`: Pottery sherds & decorated pot, 24 items.

Entries: `#minecraft:decorated_pot_sherds`, `DECORATED_POT`

<details><summary>Items</summary>

angler pottery sherd, archer pottery sherd, arms up pottery sherd, blade pottery sherd, brewer pottery sherd, burn pottery sherd, danger pottery sherd, explorer pottery sherd, flow pottery sherd, friend pottery sherd, guster pottery sherd, heart pottery sherd, heartbreak pottery sherd, howl pottery sherd, miner pottery sherd, mourner pottery sherd, plenty pottery sherd, prize pottery sherd, scrape pottery sherd, sheaf pottery sherd, shelter pottery sherd, skull pottery sherd, snort pottery sherd, decorated pot

</details>

### beds

`[beds]`: Beds, 17 items.

Entries: `*_BED`

<details><summary>Items</summary>

black bed, blue bed, brown bed, cyan bed, gray bed, green bed, light blue bed, light gray bed, lime bed, magenta bed, orange bed, pink bed, purple bed, red bed, straw bed, white bed, yellow bed

</details>

### decorations

`[decorations]`: Decorations, 44 items.

Entries: `*GOLEM_STATUE`, `*_SHELF`, `*CUSHION*`, `ARMOR_STAND`, `BOOKSHELF`, `CHISELED_BOOKSHELF`, `FLOWER_POT`, `GLOW_ITEM_FRAME`, `ITEM_FRAME`, `PAINTING`

<details><summary>Items</summary>

armor stand, black cushion, blue cushion, brown cushion, cyan cushion, glow item frame, gray cushion, green cushion, item frame, light blue cushion, light gray cushion, lime cushion, magenta cushion, orange cushion, painting, pink cushion, purple cushion, red cushion, white cushion, yellow cushion, acacia shelf, bamboo shelf, birch shelf, bookshelf, cherry shelf, chiseled bookshelf, copper golem statue, crimson shelf, dark oak shelf, exposed copper golem statue, flower pot, jungle shelf, mangrove shelf, oak shelf, oxidized copper golem statue, pale oak shelf, poplar shelf, spruce shelf, warped shelf, waxed copper golem statue, waxed exposed copper golem statue, waxed oxidized copper golem statue, waxed weathered copper golem statue, weathered copper golem statue

</details>

### wool

`[wool]`: Wool & carpet, 64 items.

Entries: `#minecraft:wool_carpets`, `*WOOL*`

<details><summary>Items</summary>

black carpet, black wool, black wool slab, black wool stairs, blue carpet, blue wool, blue wool slab, blue wool stairs, brown carpet, brown wool, brown wool slab, brown wool stairs, cyan carpet, cyan wool, cyan wool slab, cyan wool stairs, gray carpet, gray wool, gray wool slab, gray wool stairs, green carpet, green wool, green wool slab, green wool stairs, light blue carpet, light blue wool, light blue wool slab, light blue wool stairs, light gray carpet, light gray wool, light gray wool slab, light gray wool stairs, lime carpet, lime wool, lime wool slab, lime wool stairs, magenta carpet, magenta wool, magenta wool slab, magenta wool stairs, orange carpet, orange wool, orange wool slab, orange wool stairs, pink carpet, pink wool, pink wool slab, pink wool stairs, purple carpet, purple wool, purple wool slab, purple wool stairs, red carpet, red wool, red wool slab, red wool stairs, white carpet, white wool, white wool slab, white wool stairs, yellow carpet, yellow wool, yellow wool slab, yellow wool stairs

</details>

### concrete

`[concrete]`: Concrete: blocks, powder, slabs & stairs, 64 items.

Entries: `*CONCRETE*`

<details><summary>Items</summary>

black concrete, black concrete powder, black concrete slab, black concrete stairs, blue concrete, blue concrete powder, blue concrete slab, blue concrete stairs, brown concrete, brown concrete powder, brown concrete slab, brown concrete stairs, cyan concrete, cyan concrete powder, cyan concrete slab, cyan concrete stairs, gray concrete, gray concrete powder, gray concrete slab, gray concrete stairs, green concrete, green concrete powder, green concrete slab, green concrete stairs, light blue concrete, light blue concrete powder, light blue concrete slab, light blue concrete stairs, light gray concrete, light gray concrete powder, light gray concrete slab, light gray concrete stairs, lime concrete, lime concrete powder, lime concrete slab, lime concrete stairs, magenta concrete, magenta concrete powder, magenta concrete slab, magenta concrete stairs, orange concrete, orange concrete powder, orange concrete slab, orange concrete stairs, pink concrete, pink concrete powder, pink concrete slab, pink concrete stairs, purple concrete, purple concrete powder, purple concrete slab, purple concrete stairs, red concrete, red concrete powder, red concrete slab, red concrete stairs, white concrete, white concrete powder, white concrete slab, white concrete stairs, yellow concrete, yellow concrete powder, yellow concrete slab, yellow concrete stairs

</details>

### terracotta

`[terracotta]`: Terracotta: plain, dyed & glazed, 33 items.

Entries: `*TERRACOTTA*`

<details><summary>Items</summary>

black glazed terracotta, black terracotta, blue glazed terracotta, blue terracotta, brown glazed terracotta, brown terracotta, cyan glazed terracotta, cyan terracotta, gray glazed terracotta, gray terracotta, green glazed terracotta, green terracotta, light blue glazed terracotta, light blue terracotta, light gray glazed terracotta, light gray terracotta, lime glazed terracotta, lime terracotta, magenta glazed terracotta, magenta terracotta, orange glazed terracotta, orange terracotta, pink glazed terracotta, pink terracotta, purple glazed terracotta, purple terracotta, red glazed terracotta, red terracotta, terracotta, white glazed terracotta, white terracotta, yellow glazed terracotta, yellow terracotta

</details>

### glass

`[glass]`: Glass, 35 items.

Entries: `*STAINED*`, `GLASS`, `GLASS_PANE`, `TINTED_GLASS`

<details><summary>Items</summary>

black stained glass, black stained glass pane, blue stained glass, blue stained glass pane, brown stained glass, brown stained glass pane, cyan stained glass, cyan stained glass pane, glass, glass pane, gray stained glass, gray stained glass pane, green stained glass, green stained glass pane, light blue stained glass, light blue stained glass pane, light gray stained glass, light gray stained glass pane, lime stained glass, lime stained glass pane, magenta stained glass, magenta stained glass pane, orange stained glass, orange stained glass pane, pink stained glass, pink stained glass pane, purple stained glass, purple stained glass pane, red stained glass, red stained glass pane, tinted glass, white stained glass, white stained glass pane, yellow stained glass, yellow stained glass pane

</details>

### dyes

`[dyes]`: Dyes, 16 items.

Entries: `#minecraft:dyes`

<details><summary>Items</summary>

black dye, blue dye, brown dye, cyan dye, gray dye, green dye, light blue dye, light gray dye, lime dye, magenta dye, orange dye, pink dye, purple dye, red dye, white dye, yellow dye

</details>

### mushrooms

`[mushrooms]`: Mushrooms, 6 items.

Entries: `#minecraft:mushrooms`, `BROWN_MUSHROOM_BLOCK`, `MUSHROOM_STEM`, `RED_MUSHROOM_BLOCK`

<details><summary>Items</summary>

brown mushroom, brown mushroom block, mushroom stem, red mushroom, red mushroom block, shelf mushroom

</details>

### flowers

`[flowers]`: Flowers, 27 items.

Entries: `ALLIUM`, `AZURE_BLUET`, `BLUE_ORCHID`, `CACTUS_FLOWER`, `CLOSED_EYEBLOSSOM`, `CORNFLOWER`, `DANDELION`, `FLOWERING_AZALEA`, `GOLDEN_DANDELION`, `LILAC`, `LILY_OF_THE_VALLEY`, `OPEN_EYEBLOSSOM`, `ORANGE_TULIP`, `OXEYE_DAISY`, `PEONY`, `PINK_PETALS`, `PINK_TULIP`, `PITCHER_PLANT`, `POPPY`, `RED_TULIP`, `ROSE_BUSH`, `SPORE_BLOSSOM`, `SUNFLOWER`, `TORCHFLOWER`, `WHITE_TULIP`, `WILDFLOWERS`, `WITHER_ROSE`

<details><summary>Items</summary>

allium, azure bluet, blue orchid, cactus flower, closed eyeblossom, cornflower, dandelion, flowering azalea, golden dandelion, lilac, lily of the valley, open eyeblossom, orange tulip, oxeye daisy, peony, pink petals, pink tulip, pitcher plant, poppy, red tulip, rose bush, spore blossom, sunflower, torchflower, white tulip, wildflowers, wither rose

</details>

### foliage

`[foliage]`: Saplings & leaves, 26 items.

Entries: `*SAPLING*`, `*_POPLAR_LEAVES`, `ACACIA_LEAVES`, `AZALEA`, `AZALEA_LEAVES`, `BIRCH_LEAVES`, `CHERRY_LEAVES`, `DARK_OAK_LEAVES`, `FLOWERING_AZALEA_LEAVES`, `JUNGLE_LEAVES`, `LEAF_LITTER`, `MANGROVE_LEAVES`, `MANGROVE_PROPAGULE`, `OAK_LEAVES`, `PALE_OAK_LEAVES`, `SPRUCE_LEAVES`

<details><summary>Items</summary>

acacia leaves, acacia sapling, azalea, azalea leaves, birch leaves, birch sapling, cherry leaves, cherry sapling, dark oak leaves, dark oak sapling, flowering azalea leaves, jungle leaves, jungle sapling, leaf litter, mangrove leaves, mangrove propagule, oak leaves, oak sapling, orange poplar leaves, pale oak leaves, pale oak sapling, poplar sapling, red poplar leaves, spruce leaves, spruce sapling, yellow poplar leaves

</details>

### sculk

`[sculk]`: Sculk, 4 items.

Entries: `SCULK`, `SCULK_CATALYST`, `SCULK_SHRIEKER`, `SCULK_VEIN`

<details><summary>Items</summary>

sculk, sculk catalyst, sculk shrieker, sculk vein

</details>

### sulfur

`[sulfur]`: Sulfur & cinnabar, 28 items.

Entries: `*_SULFUR`, `*CINNABAR*`, `SULFUR_BRICK_*`, `POLISHED_SULFUR_*`, `SULFUR`, `SULFUR_BRICKS`, `SULFUR_SLAB`, `SULFUR_SPIKE`, `SULFUR_STAIRS`, `SULFUR_WALL`

<details><summary>Items</summary>

chiseled cinnabar, chiseled sulfur, cinnabar, cinnabar brick slab, cinnabar brick stairs, cinnabar brick wall, cinnabar bricks, cinnabar slab, cinnabar stairs, cinnabar wall, polished cinnabar, polished cinnabar slab, polished cinnabar stairs, polished cinnabar wall, polished sulfur, polished sulfur slab, polished sulfur stairs, polished sulfur wall, potent sulfur, sulfur, sulfur brick slab, sulfur brick stairs, sulfur brick wall, sulfur bricks, sulfur slab, sulfur spike, sulfur stairs, sulfur wall

</details>

### ocean

`[ocean]`: Ocean, 45 items.

Entries: `*CORAL*`, `DARK_PRISMARINE`, `DARK_PRISMARINE_SLAB`, `DARK_PRISMARINE_STAIRS`, `KELP`, `PRISMARINE`, `PRISMARINE_BRICKS`, `PRISMARINE_BRICK_SLAB`, `PRISMARINE_BRICK_STAIRS`, `PRISMARINE_SLAB`, `PRISMARINE_STAIRS`, `PRISMARINE_WALL`, `SEAGRASS`, `SEA_PICKLE`, `SPONGE`, `WET_SPONGE`

<details><summary>Items</summary>

brain coral, brain coral block, brain coral fan, bubble coral, bubble coral block, bubble coral fan, dark prismarine, dark prismarine slab, dark prismarine stairs, dead brain coral, dead brain coral block, dead brain coral fan, dead bubble coral, dead bubble coral block, dead bubble coral fan, dead fire coral, dead fire coral block, dead fire coral fan, dead horn coral, dead horn coral block, dead horn coral fan, dead tube coral, dead tube coral block, dead tube coral fan, fire coral, fire coral block, fire coral fan, horn coral, horn coral block, horn coral fan, kelp, prismarine, prismarine brick slab, prismarine brick stairs, prismarine bricks, prismarine slab, prismarine stairs, prismarine wall, sea pickle, seagrass, sponge, tube coral, tube coral block, tube coral fan, wet sponge

</details>

### nether

`[nether]`: Nether, 45 items.

Entries: `*BASALT*`, `*_BLACKSTONE`, `BLACKSTONE_*`, `RED_NETHER_*`, `NETHER_BRICK_*`, `POLISHED_BLACKSTONE_BRICK_*`, `BLACKSTONE`, `CHISELED_NETHER_BRICKS`, `CRACKED_NETHER_BRICKS`, `CRACKED_POLISHED_BLACKSTONE_BRICKS`, `CRIMSON_FUNGUS`, `CRIMSON_NYLIUM`, `CRIMSON_ROOTS`, `MAGMA_BLOCK`, `NETHERRACK`, `NETHER_BRICK`, `NETHER_BRICKS`, `NETHER_SPROUTS`, `NETHER_WART_BLOCK`, `POLISHED_BLACKSTONE_BRICKS`, `POLISHED_BLACKSTONE_SLAB`, `POLISHED_BLACKSTONE_STAIRS`, `POLISHED_BLACKSTONE_WALL`, `SOUL_SAND`, `SOUL_SOIL`, `TWISTING_VINES`, `WARPED_FUNGUS`, `WARPED_NYLIUM`, `WARPED_ROOTS`, `WARPED_WART_BLOCK`, `WEEPING_VINES`

<details><summary>Items</summary>

nether brick, basalt, blackstone, blackstone slab, blackstone stairs, blackstone wall, chiseled nether bricks, chiseled polished blackstone, cracked nether bricks, cracked polished blackstone bricks, crimson fungus, crimson nylium, crimson roots, gilded blackstone, magma block, nether brick fence, nether brick slab, nether brick stairs, nether brick wall, nether bricks, nether sprouts, nether wart block, netherrack, polished basalt, polished blackstone, polished blackstone brick slab, polished blackstone brick stairs, polished blackstone brick wall, polished blackstone bricks, polished blackstone slab, polished blackstone stairs, polished blackstone wall, red nether brick slab, red nether brick stairs, red nether brick wall, red nether bricks, smooth basalt, soul sand, soul soil, twisting vines, warped fungus, warped nylium, warped roots, warped wart block, weeping vines

</details>

### end

`[end]`: The End, 11 items.

Entries: `*PURPUR*`, `END_STONE_*`, `CHORUS_FLOWER`, `END_STONE`, `POPPED_CHORUS_FRUIT`

<details><summary>Items</summary>

popped chorus fruit, chorus flower, end stone, end stone brick slab, end stone brick stairs, end stone brick wall, end stone bricks, purpur block, purpur pillar, purpur slab, purpur stairs

</details>

### copper

`[copper]`: Copper blocks, 80 items.

Entries: `#minecraft:copper`, `*GRATE*`, `*_CUT_COPPER`, `*_COPPER_BARS`, `*_COPPER_DOOR`, `*_COPPER_SLAB`, `*_COPPER_CHAIN`, `*_COPPER_STAIRS`, `*_CHISELED_COPPER`, `*_COPPER_TRAPDOOR`, `CHISELED_COPPER`, `COPPER_BARS`, `COPPER_CHAIN`, `COPPER_DOOR`, `COPPER_TRAPDOOR`, `CUT_COPPER`

<details><summary>Items</summary>

chiseled copper, copper bars, copper block, copper chain, copper door, copper grate, copper trapdoor, cut copper, cut copper slab, cut copper stairs, exposed chiseled copper, exposed copper, exposed copper bars, exposed copper chain, exposed copper door, exposed copper grate, exposed copper trapdoor, exposed cut copper, exposed cut copper slab, exposed cut copper stairs, oxidized chiseled copper, oxidized copper, oxidized copper bars, oxidized copper chain, oxidized copper door, oxidized copper grate, oxidized copper trapdoor, oxidized cut copper, oxidized cut copper slab, oxidized cut copper stairs, waxed chiseled copper, waxed copper bars, waxed copper block, waxed copper chain, waxed copper door, waxed copper grate, waxed copper trapdoor, waxed cut copper, waxed cut copper slab, waxed cut copper stairs, waxed exposed chiseled copper, waxed exposed copper, waxed exposed copper bars, waxed exposed copper chain, waxed exposed copper door, waxed exposed copper grate, waxed exposed copper trapdoor, waxed exposed cut copper, waxed exposed cut copper slab, waxed exposed cut copper stairs, waxed oxidized chiseled copper, waxed oxidized copper, waxed oxidized copper bars, waxed oxidized copper chain, waxed oxidized copper door, waxed oxidized copper grate, waxed oxidized copper trapdoor, waxed oxidized cut copper, waxed oxidized cut copper slab, waxed oxidized cut copper stairs, waxed weathered chiseled copper, waxed weathered copper, waxed weathered copper bars, waxed weathered copper chain, waxed weathered copper door, waxed weathered copper grate, waxed weathered copper trapdoor, waxed weathered cut copper, waxed weathered cut copper slab, waxed weathered cut copper stairs, weathered chiseled copper, weathered copper, weathered copper bars, weathered copper chain, weathered copper door, weathered copper grate, weathered copper trapdoor, weathered cut copper, weathered cut copper slab, weathered cut copper stairs

</details>

### logs

`[logs]`: Logs & planks, 64 items.

Entries: `#minecraft:logs`, `#minecraft:planks`, `BAMBOO_BLOCK`, `BAMBOO_MOSAIC`, `STRIPPED_BAMBOO_BLOCK`

<details><summary>Items</summary>

acacia log, acacia planks, acacia wood, bamboo block, bamboo mosaic, bamboo planks, birch log, birch planks, birch wood, cherry log, cherry planks, cherry wood, crimson hyphae, crimson planks, crimson stem, dark oak log, dark oak planks, dark oak wood, jungle log, jungle planks, jungle wood, mangrove log, mangrove planks, mangrove wood, oak log, oak planks, oak wood, pale oak log, pale oak planks, pale oak wood, poplar log, poplar planks, poplar wood, spruce log, spruce planks, spruce wood, stripped acacia log, stripped acacia wood, stripped bamboo block, stripped birch log, stripped birch wood, stripped cherry log, stripped cherry wood, stripped crimson hyphae, stripped crimson stem, stripped dark oak log, stripped dark oak wood, stripped jungle log, stripped jungle wood, stripped mangrove log, stripped mangrove wood, stripped oak log, stripped oak wood, stripped pale oak log, stripped pale oak wood, stripped poplar log, stripped poplar wood, stripped spruce log, stripped spruce wood, stripped warped hyphae, stripped warped stem, warped hyphae, warped planks, warped stem

</details>

### wooden-building

`[wooden building]`: Wooden building, 80 items.

Entries: `#minecraft:fence_gates`, `#minecraft:wooden_doors`, `#minecraft:wooden_slabs`, `#minecraft:wooden_fences`, `#minecraft:wooden_stairs`, `#minecraft:wooden_trapdoors`, `BAMBOO_MOSAIC_SLAB`, `BAMBOO_MOSAIC_STAIRS`

<details><summary>Items</summary>

acacia door, acacia fence, acacia fence gate, acacia slab, acacia stairs, acacia trapdoor, bamboo door, bamboo fence, bamboo fence gate, bamboo mosaic slab, bamboo mosaic stairs, bamboo slab, bamboo stairs, bamboo trapdoor, birch door, birch fence, birch fence gate, birch slab, birch stairs, birch trapdoor, cherry door, cherry fence, cherry fence gate, cherry slab, cherry stairs, cherry trapdoor, crimson door, crimson fence, crimson fence gate, crimson slab, crimson stairs, crimson trapdoor, dark oak door, dark oak fence, dark oak fence gate, dark oak slab, dark oak stairs, dark oak trapdoor, jungle door, jungle fence, jungle fence gate, jungle slab, jungle stairs, jungle trapdoor, mangrove door, mangrove fence, mangrove fence gate, mangrove slab, mangrove stairs, mangrove trapdoor, oak door, oak fence, oak fence gate, oak slab, oak stairs, oak trapdoor, pale oak door, pale oak fence, pale oak fence gate, pale oak slab, pale oak stairs, pale oak trapdoor, poplar door, poplar fence, poplar fence gate, poplar slab, poplar stairs, poplar trapdoor, spruce door, spruce fence, spruce fence gate, spruce slab, spruce stairs, spruce trapdoor, warped door, warped fence, warped fence gate, warped slab, warped stairs, warped trapdoor

</details>

### dirt-and-sand

`[dirt and sand]`: Dirt, sand & gravel, 12 items.

Entries: `#minecraft:dirt`, `CLAY`, `GRASS_BLOCK`, `GRAVEL`, `MUD`, `MYCELIUM`, `PACKED_MUD`, `PODZOL`, `RED_SAND`, `SAND`

<details><summary>Items</summary>

clay, coarse dirt, dirt, grass block, gravel, mud, mycelium, packed mud, podzol, red sand, rooted dirt, sand

</details>

### ice-and-snow

`[ice and snow]`: Ice & snow, 6 items.

Entries: `BLUE_ICE`, `ICE`, `PACKED_ICE`, `SNOW`, `SNOWBALL`, `SNOW_BLOCK`

<details><summary>Items</summary>

snowball, blue ice, ice, packed ice, snow, snow block

</details>

### plants

`[plants]`: Plants, 26 items.

Entries: `*_GRASS`, `BAMBOO`, `BIG_DRIPLEAF`, `BUSH`, `CACTUS`, `COBWEB`, `DEAD_BUSH`, `FERN`, `FIREFLY_BUSH`, `GLOW_LICHEN`, `HANGING_ROOTS`, `LARGE_FERN`, `LILY_PAD`, `MANGROVE_ROOTS`, `MOSS_BLOCK`, `MOSS_CARPET`, `MUDDY_MANGROVE_ROOTS`, `PALE_HANGING_MOSS`, `PALE_MOSS_BLOCK`, `PALE_MOSS_CARPET`, `RED_SHRUB`, `SMALL_DRIPLEAF`, `VINE`

<details><summary>Items</summary>

bamboo, big dripleaf, bush, cactus, cobweb, dead bush, fern, firefly bush, glow lichen, hanging roots, large fern, lily pad, mangrove roots, moss block, moss carpet, muddy mangrove roots, pale hanging moss, pale moss block, pale moss carpet, red shrub, short dry grass, short grass, small dripleaf, tall dry grass, tall grass, vine

</details>

### stone

`[stone]`: Stone, 47 items.

Entries: `#minecraft:stone_bricks`, `MOSSY_COBBLESTONE*`, `MOSSY_STONE_BRICK*`, `*DIORITE*`, `*GRANITE*`, `*ANDESITE*`, `COBBLESTONE*`, `STONE_BRICK_*`, `CALCITE`, `DRIPSTONE_BLOCK`, `POINTED_DRIPSTONE`, `SMOOTH_STONE`, `SMOOTH_STONE_SLAB`, `STONE`, `STONE_SLAB`, `STONE_STAIRS`

<details><summary>Items</summary>

andesite, andesite slab, andesite stairs, andesite wall, calcite, chiseled stone bricks, cobblestone, cobblestone slab, cobblestone stairs, cobblestone wall, cracked stone bricks, diorite, diorite slab, diorite stairs, diorite wall, dripstone block, granite, granite slab, granite stairs, granite wall, mossy cobblestone, mossy cobblestone slab, mossy cobblestone stairs, mossy cobblestone wall, mossy stone brick slab, mossy stone brick stairs, mossy stone brick wall, mossy stone bricks, pointed dripstone, polished andesite, polished andesite slab, polished andesite stairs, polished diorite, polished diorite slab, polished diorite stairs, polished granite, polished granite slab, polished granite stairs, smooth stone, smooth stone slab, stone, stone brick slab, stone brick stairs, stone brick wall, stone bricks, stone slab, stone stairs

</details>

### deepslate

`[deepslate]`: Deepslate, 20 items.

Entries: `DEEPSLATE`, `CHISELED_DEEPSLATE`, `COBBLED_DEEPSLATE*`, `POLISHED_DEEPSLATE*`, `DEEPSLATE_BRICK*`, `DEEPSLATE_TILE*`, `CRACKED_DEEPSLATE_*`

<details><summary>Items</summary>

chiseled deepslate, cobbled deepslate, cobbled deepslate slab, cobbled deepslate stairs, cobbled deepslate wall, cracked deepslate bricks, cracked deepslate tiles, deepslate, deepslate brick slab, deepslate brick stairs, deepslate brick wall, deepslate bricks, deepslate tile slab, deepslate tile stairs, deepslate tile wall, deepslate tiles, polished deepslate, polished deepslate slab, polished deepslate stairs, polished deepslate wall

</details>

### tuff

`[tuff]`: Tuff, 14 items.

Entries: `*TUFF*`

<details><summary>Items</summary>

chiseled tuff, chiseled tuff bricks, polished tuff, polished tuff slab, polished tuff stairs, polished tuff wall, tuff, tuff brick slab, tuff brick stairs, tuff brick wall, tuff bricks, tuff slab, tuff stairs, tuff wall

</details>

### sandstone

`[sandstone]`: Sandstone, 20 items.

Entries: `*SANDSTONE*`

<details><summary>Items</summary>

chiseled red sandstone, chiseled sandstone, cut red sandstone, cut red sandstone slab, cut sandstone, cut sandstone slab, red sandstone, red sandstone slab, red sandstone stairs, red sandstone wall, sandstone, sandstone slab, sandstone stairs, sandstone wall, smooth red sandstone, smooth red sandstone slab, smooth red sandstone stairs, smooth sandstone, smooth sandstone slab, smooth sandstone stairs

</details>

### resin

`[resin]`: Resin, 8 items.

Entries: `*RESIN*`

<details><summary>Items</summary>

resin brick, chiseled resin bricks, resin block, resin brick slab, resin brick stairs, resin brick wall, resin bricks, resin clump

</details>

### building

`[building]`: Bricks, quartz & other building, 29 items.

Entries: `MUD_*`, `BRICK_*`, `QUARTZ_*`, `RESIN_BRICK_*`, `BRICKS`, `CHISELED_QUARTZ_BLOCK`, `CHISELED_RESIN_BRICKS`, `CRYING_OBSIDIAN`, `IRON_BARS`, `IRON_CHAIN`, `LADDER`, `OBSIDIAN`, `RESIN_BLOCK`, `RESIN_BRICKS`, `SCAFFOLDING`, `SMOOTH_QUARTZ`, `SMOOTH_QUARTZ_SLAB`, `SMOOTH_QUARTZ_STAIRS`

<details><summary>Items</summary>

brick slab, brick stairs, brick wall, bricks, chiseled quartz block, chiseled resin bricks, crying obsidian, iron bars, iron chain, ladder, mud brick slab, mud brick stairs, mud brick wall, mud bricks, obsidian, quartz block, quartz bricks, quartz pillar, quartz slab, quartz stairs, resin block, resin brick slab, resin brick stairs, resin brick wall, resin bricks, scaffolding, smooth quartz, smooth quartz slab, smooth quartz stairs

</details>

<!-- end of built-in groups -->
