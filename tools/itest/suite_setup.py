"""setup: unusual chests, items and crowds."""
from arena import SIZE, Y, Trigger, _count, conserved, expect, scenario


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


# ---- trapdoors (avoid-trapdoors)
# Vanilla golems path onto open trapdoors as if they were floor: with three trapdoor-covered
# holes across their path (like a ladder shaft), they fall in (see option-avoid-trapdoors-off).

OPEN_TRAPDOOR = "oak_trapdoor[half=top,open=true,facing=east]"


def trapdoor_holes(c, block, dzs=(9, 10, 11)):
    """Holes 2 deep at dx=8 across the line from the copper chest (dx=2) to the log chest (dx=14),
    each covered by a trapdoor in the floor. Floor elsewhere: a golem can walk around."""
    for dz in dzs:
        x, y, z = c.abs(8, dz)
        c.run(f"setblock {x} {y - 3} {z} minecraft:glass")
        c.run(f"setblock {x} {y - 1} {z} minecraft:{block}")


def trapdoor_scene(c):
    c.chest("copper", 2, 10, [("birch_log", 8)], block="waxed_copper_chest")
    c.chest("logs", 14, 10, [("oak_log", 1)])
    c.golem(4, 10)


def golems_in(c, dx, y, dy, dz_from, dz_to):
    """Golems whose hitbox reaches into the column at dx between dz_from and dz_to, from y to y + dy + 1."""
    x, _, z = c.abs(dx, dz_from)
    return _count(c.r.cmd(f"execute if entity @e[type=minecraft:copper_golem,x={x},y={y},z={z},"
                          f"dx=0,dy={dy},dz={dz_to - dz_from}]"))


def fell_in_holes(c):
    return golems_in(c, 8, Y - 2, 0, 9, 11) > 0


@scenario("trapdoor-holes", "setup", 300, "Three holes covered by open trapdoors lie across the golem's path: it walks around and delivers the birch logs without falling in.")
def _(c):
    trapdoor_holes(c, OPEN_TRAPDOOR)
    trapdoor_scene(c)
    fell = Trigger()
    def check():
        fell.fire_when(lambda: fell_in_holes(c), lambda: None)
        return [expect(c, "logs", "birch_log", 8), (fell.at is None, "no golem fell through a trapdoor")]
    return check


@scenario("trapdoor-closed", "setup", 300, "A strip of closed trapdoors lies across the golem's path: it walks around them, never on them.")
def _(c):
    x, y, z = c.abs(8, 3)
    _, _, z2 = c.abs(8, SIZE - 1)
    c.run(f"fill {x} {y} {z} {x} {y} {z2} minecraft:spruce_trapdoor[half=bottom,open=false]")
    trapdoor_scene(c)
    stepped = Trigger()
    def check():
        stepped.fire_when(lambda: golems_in(c, 8, Y, 0, 3, SIZE - 1) > 0, lambda: None)
        return [expect(c, "logs", "birch_log", 8), (stepped.at is None, "no golem stood on a trapdoor")]
    return check
