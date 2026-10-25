"""traffic: golems meeting head-on give way (golems-give-way).

Layout: two rooms joined by a corridor one block wide along z=10 (x 6..14).
West room: copper chest of birch logs, a [stone] chest, golem A. East room: copper
chest of granite, a [logs] chest, golem B. Each golem carries its item through the
corridor toward the other, so they meet inside it, over and over until both copper
chests are empty.
"""
from arena import Y, conserved, expect, scenario


def corridor(c, niche=False, floor=None):
    x, z = c.x, c.z
    for z1, z2 in ((0, 9), (11, 20)):  # solid glass either side of the corridor
        c.run(f"fill {x + 6} {Y} {z + z1} {x + 14} {Y + 2} {z + z2} minecraft:glass")
    if niche:  # a one-block pocket halfway along, where one golem can step aside
        c.run(f"fill {x + 10} {Y} {z + 9} {x + 10} {Y + 2} {z + 9} minecraft:air")
    if floor:
        c.run(f"fill {x} {Y} {z} {x + 20} {Y} {z + 20} minecraft:{floor} replace minecraft:air")
    dy = 0
    c.chest("copperW", 1, 8, [("birch_log", 16), ("birch_log", 16)], block="waxed_copper_chest", dy=dy)
    c.chest("stone", 1, 12, dy=dy)
    c.sign(1, 12, "[stone]", dy=dy)
    c.chest("copperE", 19, 8, [("granite", 16), ("granite", 16)], block="waxed_copper_chest", dy=dy)
    c.chest("logs", 19, 12, dy=dy)
    c.sign(19, 12, "[logs]", dy=dy)
    c.golem(3, 10)
    c.golem(17, 10)
    return lambda: [expect(c, "logs", "birch_log", 32), expect(c, "stone", "granite", 32),
                    conserved(c, "birch_log", 32), conserved(c, "granite", 32)]


@scenario("give-way-niche", "traffic", 420, "Two golems meet head-on in a 1-wide corridor with a side pocket: they get past each other and every item arrives.")
def _(c):
    return corridor(c, niche=True)


@scenario("give-way-no-niche", "traffic", 420, "Two golems meet head-on in a 1-wide corridor with no side pocket: one backs out so the other can pass; every item arrives.")
def _(c):
    return corridor(c)


@scenario("give-way-carpet", "traffic", 420, "The same corridor with a pocket, on a carpeted floor: carpet doesn't stop a golem stepping aside.")
def _(c):
    return corridor(c, niche=True, floor="white_carpet")


@scenario("give-way-slabs", "traffic", 420, "The same corridor with a pocket, on a floor of bottom slabs.")
def _(c):
    return corridor(c, niche=True, floor="smooth_stone_slab")
