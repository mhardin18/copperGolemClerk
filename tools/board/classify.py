import json, os, sys, fnmatch
S = sys.argv[1]
tagdir = os.path.join(S, 'tags/data/minecraft/tags/item')
comp = os.path.join(S, 'datagen/out/reports/minecraft/components/item')
def tag(name):
    p = os.path.join(tagdir, name + '.json')
    if not os.path.exists(p): raise SystemExit('missing tag ' + name)
    out = set()
    for v in json.load(open(p))['values']:
        v = v if isinstance(v, str) else v['id']
        out |= tag(v[1:].split(':')[-1]) if v.startswith('#') else {v.split(':')[-1].upper()}
    return out
C = {f[:-5].upper(): json.load(open(os.path.join(comp, f)))['components'] for f in os.listdir(comp)}
EXCL = set("""AIR BARRIER BEDROCK BUDDING_AMETHYST CHAIN_COMMAND_BLOCK CHORUS_PLANT COMMAND_BLOCK COMMAND_BLOCK_MINECART
DEBUG_STICK DIRT_PATH END_PORTAL_FRAME FARMLAND FROGSPAWN JIGSAW KNOWLEDGE_BOOK LIGHT PETRIFIED_OAK_SLAB PLAYER_HEAD
REINFORCED_DEEPSLATE REPEATING_COMMAND_BLOCK SPAWNER STRUCTURE_BLOCK STRUCTURE_VOID SUSPICIOUS_GRAVEL SUSPICIOUS_SAND
TEST_BLOCK TEST_INSTANCE_BLOCK TRIAL_SPAWNER VAULT""".split())
items = sorted(i for i in C if i not in EXCL and not i.startswith('INFESTED_') and not i.endswith('_SPAWN_EGG'))
def m(*specs):
    """matcher from names, *patterns and #tags"""
    names, pats, tags = set(), [], set()
    for s in specs:
        for t in s.split():
            if t.startswith('#'): tags |= tag(t[1:])
            elif '*' in t: pats.append(t)
            else: names.add(t)
    return lambda i: i in names or i in tags or any(fnmatch.fnmatchcase(i, p) for p in pats)
food = lambda i: 'minecraft:food' in C[i]
disc = lambda i: 'minecraft:jukebox_playable' in C[i]
# Ordered: first match wins. (id, display name, matcher)
CATS = [
 ('music-discs', 'Music discs', lambda i: disc(i) or i == 'DISC_FRAGMENT_5'),
 ('smithing-templates', 'Smithing templates', m('*_SMITHING_TEMPLATE')),
 ('rare', 'Rare & trophies', m('DRAGON_EGG NETHER_STAR TOTEM_OF_UNDYING HEART_OF_THE_SEA HEAVY_CORE CONDUIT BEACON END_CRYSTAL TRIAL_KEY OMINOUS_TRIAL_KEY OMINOUS_BOTTLE SNIFFER_EGG TURTLE_EGG DRIED_GHAST CREAKING_HEART GOAT_HORN ECHO_SHARD ENCHANTED_GOLDEN_APPLE')),
 ('spawn-and-ops', None, lambda i: False),
 ('buckets', 'Buckets', m('*BUCKET')),
 ('potions-brewing', 'Potions & brewing', m('POTION SPLASH_POTION LINGERING_POTION GLASS_BOTTLE BLAZE_POWDER DRAGON_BREATH FERMENTED_SPIDER_EYE GLISTERING_MELON_SLICE NETHER_WART')),
 ('books-enchanting', 'Books & enchanting', m('BOOK WRITABLE_BOOK WRITTEN_BOOK ENCHANTED_BOOK EXPERIENCE_BOTTLE')),
 ('maps-navigation', 'Maps & navigation', m('*_MAP MAP FILLED_MAP COMPASS RECOVERY_COMPASS CLOCK SPYGLASS LODESTONE')),
 ('fireworks', 'Fireworks', m('FIREWORK_ROCKET FIREWORK_STAR')),
 ('food', 'Food', lambda i: (food(i) and i not in ('ROTTEN_FLESH', 'SPIDER_EYE')) or i == 'CAKE'),
 ('seeds', 'Seeds', m('*_SEEDS PITCHER_POD COCOA_BEANS')),
 ('farming', 'Crops & farming', m('WHEAT HAY_BLOCK PUMPKIN CARVED_PUMPKIN MELON SUGAR_CANE SUGAR BONE_MEAL BONE_BLOCK BEEHIVE BEE_NEST HONEYCOMB HONEYCOMB_BLOCK DRIED_KELP_BLOCK')),
 ('mob-drops', 'Mob drops', m('ROTTEN_FLESH BONE STRING SPIDER_EYE GUNPOWDER ENDER_PEARL SLIME_BALL LEATHER FEATHER PHANTOM_MEMBRANE BLAZE_ROD GHAST_TEAR MAGMA_CREAM INK_SAC GLOW_INK_SAC BREEZE_ROD RABBIT_FOOT RABBIT_HIDE SHULKER_SHELL ARMADILLO_SCUTE TURTLE_SCUTE NAUTILUS_SHELL EGG BLUE_EGG BROWN_EGG RESIN_CLUMP ENDER_EYE')),
 ('ores', 'Ores', m('*_ORE ANCIENT_DEBRIS')),
 ('mineral-blocks', 'Mineral blocks', m('COAL_BLOCK IRON_BLOCK GOLD_BLOCK DIAMOND_BLOCK EMERALD_BLOCK LAPIS_BLOCK NETHERITE_BLOCK REDSTONE_BLOCK RAW_*_BLOCK')),
 ('ingots-gems', 'Ingots & gems', m('RAW_* *_INGOT *_NUGGET DIAMOND EMERALD LAPIS_LAZULI COAL CHARCOAL QUARTZ NETHERITE_SCRAP PRISMARINE_SHARD PRISMARINE_CRYSTALS')),
 ('crafting-materials', 'Crafting materials', m('STICK BOWL BRICK NETHER_BRICK RESIN_BRICK CLAY_BALL FLINT PAPER GLOWSTONE_DUST')),
 ('amethyst', 'Amethyst', m('*AMETHYST*')),
 ('animal-gear', 'Saddles & animal armor', m('SADDLE *_HARNESS LEAD *_HORSE_ARMOR *_NAUTILUS_ARMOR WOLF_ARMOR')),
 ('armor', 'Armor', m('*_HELMET *_CHESTPLATE *_LEGGINGS *_BOOTS SHIELD ELYTRA')),
 ('weapons', 'Weapons & ammo', m('*_SWORD *_SPEAR BOW CROSSBOW TRIDENT MACE ARROW SPECTRAL_ARROW TIPPED_ARROW FIRE_CHARGE WIND_CHARGE')),
 ('tools', 'Tools', m('*_PICKAXE *_AXE *_SHOVEL *_HOE SHEARS FLINT_AND_STEEL FISHING_ROD BRUSH NAME_TAG CARROT_ON_A_STICK WARPED_FUNGUS_ON_A_STICK')),
 ('boats', 'Boats', m('*_BOAT *_RAFT')),
 ('rails', 'Rails & minecarts', m('#rails MINECART *_MINECART')),
 ('redstone', 'Redstone', m('REDSTONE REDSTONE_TORCH REPEATER COMPARATOR PISTON STICKY_PISTON OBSERVER HOPPER DROPPER DISPENSER LEVER #buttons *_PRESSURE_PLATE DAYLIGHT_DETECTOR TARGET TRIPWIRE_HOOK REDSTONE_LAMP NOTE_BLOCK TNT CRAFTER SCULK_SENSOR CALIBRATED_SCULK_SENSOR *LIGHTNING_ROD SLIME_BLOCK HONEY_BLOCK IRON_DOOR IRON_TRAPDOOR *COPPER_BULB')),
 ('light', 'Light sources', m('TORCH SOUL_TORCH COPPER_TORCH *LANTERN GLOWSTONE SHROOMLIGHT *_FROGLIGHT END_ROD CAMPFIRE SOUL_CAMPFIRE CANDLE *_CANDLE')),
 ('storage', 'Storage', m('CHEST TRAPPED_CHEST ENDER_CHEST *SHULKER_BOX *BUNDLE #copper_chests')),
 ('workstations', 'Workstations (villager jobs)', m('BLAST_FURNACE SMOKER CARTOGRAPHY_TABLE BREWING_STAND COMPOSTER BARREL FLETCHING_TABLE CAULDRON LECTERN STONECUTTER LOOM SMITHING_TABLE GRINDSTONE')),
 ('utility', 'Utility blocks', m('CRAFTING_TABLE FURNACE ANVIL CHIPPED_ANVIL DAMAGED_ANVIL ENCHANTING_TABLE JUKEBOX BELL RESPAWN_ANCHOR')),
 ('decorations', 'Decorations', m('#banners *_BANNER_PATTERN *_BED #signs #hanging_signs #skulls ITEM_FRAME GLOW_ITEM_FRAME PAINTING FLOWER_POT ARMOR_STAND DECORATED_POT #decorated_pot_sherds *COPPER_GOLEM_STATUE *BOOKSHELF *_SHELF *_CUSHION')),
 ('wool', 'Wool & carpet', m('#wool #wool_carpets #wool_slabs #wool_stairs')),
 ('concrete-terracotta', 'Concrete & terracotta', m('*_CONCRETE *_CONCRETE_POWDER #concrete_slabs #concrete_stairs TERRACOTTA *_TERRACOTTA')),
 ('glass', 'Glass', m('GLASS *_GLASS GLASS_PANE *_GLASS_PANE')),
 ('dyes', 'Dyes', m('*_DYE')),
 ('mushrooms', 'Mushrooms', m('#mushrooms *MUSHROOM_BLOCK MUSHROOM_STEM')),
 ('flowers', 'Flowers', m('#flowers TORCHFLOWER PITCHER_PLANT')),
 ('saplings-leaves', 'Saplings & leaves', m('#saplings #leaves LEAF_LITTER')),
 ('sculk', 'Sculk', m('*SCULK*')),
 ('sulfur', 'Sulfur & cinnabar', m('*SULFUR* *CINNABAR*')),
 ('ocean', 'Ocean', m('*CORAL* *PRISMARINE* SPONGE WET_SPONGE SEA_PICKLE KELP SEAGRASS')),
 ('nether', 'Nether', m('NETHERRACK MAGMA_BLOCK SOUL_SAND SOUL_SOIL *_NYLIUM *_WART_BLOCK *_FUNGUS CRIMSON_ROOTS WARPED_ROOTS NETHER_SPROUTS WEEPING_VINES TWISTING_VINES *BLACKSTONE* *BASALT *NETHER_BRICK* CRYING_OBSIDIAN')),
 ('end', 'The End', m('END_STONE *END_STONE_BRICK* PURPUR* CHORUS_FLOWER POPPED_CHORUS_FRUIT')),
 ('copper', 'Copper blocks', m('*COPPER*')),
 ('logs', 'Logs & planks', m('#logs #planks BAMBOO_BLOCK STRIPPED_BAMBOO_BLOCK BAMBOO_MOSAIC')),
 ('wooden-building', 'Wooden building', m('#wooden_slabs #wooden_stairs #wooden_fences #fence_gates #wooden_doors #wooden_trapdoors BAMBOO_MOSAIC_*')),
 ('dirt-sand', 'Dirt, sand & gravel', m('#dirt GRASS_BLOCK PODZOL MYCELIUM MUD PACKED_MUD CLAY #sand RED_SAND GRAVEL')),
 ('ice-snow', 'Ice & snow', m('ICE PACKED_ICE BLUE_ICE SNOW SNOW_BLOCK SNOWBALL')),
 ('plants', 'Plants', m('SHORT_GRASS TALL_GRASS FERN LARGE_FERN *DRY_GRASS DEAD_BUSH BUSH FIREFLY_BUSH VINE GLOW_LICHEN LILY_PAD CACTUS BAMBOO HANGING_ROOTS SPORE_BLOSSOM #moss_blocks *MOSS_CARPET PALE_HANGING_MOSS BIG_DRIPLEAF SMALL_DRIPLEAF *MANGROVE_ROOTS COBWEB RED_SHRUB')),
 ('stone', 'Stone & deepslate', m('STONE COBBLESTONE MOSSY_COBBLESTONE SMOOTH_STONE* STONE_SLAB STONE_STAIRS *STONE_BRICK* *COBBLESTONE_* *DEEPSLATE* *TUFF* *GRANITE* *DIORITE* *ANDESITE* CALCITE DRIPSTONE_BLOCK POINTED_DRIPSTONE')),
 ('sandstone', 'Sandstone', m('*SANDSTONE*')),
 ('other-building', 'Bricks, quartz & other building', m('BRICKS BRICK_* MUD_BRICK* *RESIN_BRICK* RESIN_BLOCK *QUARTZ* OBSIDIAN IRON_BARS IRON_CHAIN LADDER SCAFFOLDING')),
]
assign = {}; cats = []
for cid, name, fn in CATS:
    if name is None: continue
    cats.append({'id': cid, 'name': name})
for i in items:
    for cid, name, fn in CATS:
        if name and fn(i):
            assign[i] = cid; break
left = [i for i in items if i not in assign]
counts = {c['id']: 0 for c in cats}
for v in assign.values(): counts[v] += 1
for c in cats: print(f"{counts[c['id']]:>4}  {c['name']}", file=sys.stderr)
print(f"{len(left):>4}  UNSORTED: {' '.join(left)}", file=sys.stderr)
stack = {i: C[i].get('minecraft:max_stack_size', 64) for i in items}
json.dump({'categories': cats, 'assign': assign, 'items': items, 'stack': stack}, open(os.path.join(S, 'board/grouping.json'), 'w'))
