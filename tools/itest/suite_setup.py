"""setup: unusual chests, items and crowds."""
from arena import conserved, expect, scenario


@scenario("blocked-chest", "setup", 150, "The only log chest has a block on top (vanilla can't open it); the logs end up in [Overflow].")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 14, 10, [("oak_log", 1)])
    c.block(14, 10, "stone", dy=1)
    c.chest("overflow", 8, 4)
    c.sign(8, 4, "[Overflow]")
    c.golem(4, 10)
    return lambda: [expect(c, "overflow", "birch_log", 16), expect(c, "logs", "birch_log", 0), conserved(c, "birch_log", 16)]


@scenario("trapped-chest-and-barrel", "setup", 150, "A trapped chest is a valid home; a barrel holding logs is ignored, as in vanilla.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("barrel", 7, 10, [("oak_log", 1)], block="barrel")
    c.chest("trapped", 14, 10, [("oak_log", 1)], block="trapped_chest")
    c.golem(4, 10)
    return lambda: [expect(c, "trapped", "birch_log", 16), expect(c, "barrel", "birch_log", 0)]


@scenario("enchanted-renamed-item", "setup", 150, "An enchanted, renamed, damaged stone pickaxe still matches a framed stone pickaxe label.")
def _(c):
    fancy = 'stone_pickaxe[minecraft:enchantments={"minecraft:efficiency":3},minecraft:custom_name="Bob",minecraft:damage=50]'
    c.chest("copper", 2, 10, [(fancy, 1)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("labelled", 14, 10)
    c.frame(14, 10, "stone_pickaxe")
    c.golem(4, 10)
    return lambda: [expect(c, "labelled", "stone_pickaxe", 1), expect(c, "empty", "*", 0)]


@scenario("partial-delivery", "setup", 180, "The log chest has room for 4 more birch logs: 4 go in, the other 12 go to [Overflow].")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 14, 10, [("oak_log", 64)] * 26 + [("birch_log", 60)])
    c.chest("overflow", 8, 4)
    c.sign(8, 4, "[Overflow]")
    c.golem(4, 10)
    return lambda: [expect(c, "logs", "birch_log", 64), expect(c, "overflow", "birch_log", 12), conserved(c, "birch_log", 76)]


@scenario("crowded-chest", "setup", 360, "Four golems share one copper chest and one log chest; all 64 birch logs arrive, none lost.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)] * 4, block="waxed_copper_chest")
    c.chest("logs", 16, 10, [("oak_log", 1)])
    for dz in (6, 8, 12, 14):
        c.golem(4, dz)
    return lambda: [expect(c, "logs", "birch_log", 64), conserved(c, "birch_log", 64)]
