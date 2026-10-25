"""core: the main features working as intended.

Layout: copper chest at (2,10), golem at (4,10); other chests further along +x.
"""
from arena import expect, scenario


@scenario("group-match", "core", 150, "Birch logs go to the chest holding an oak log, past a nearer empty chest.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("logs", 14, 10, [("oak_log", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "logs", "birch_log", 16), expect(c, "empty", "birch_log", 0)]


@scenario("match-mode-all", "core", 120, "A log chest with a stray dirt block is not a group match; birch logs use the empty chest.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("mixed", 8, 10, [("oak_log", 1), ("dirt", 1)])
    c.chest("empty", 14, 10)
    c.golem(4, 10)
    return lambda: [expect(c, "empty", "birch_log", 16), expect(c, "mixed", "birch_log", 0)]


@scenario("frame-label", "core", 200, "A framed stone pickaxe takes only stone pickaxes; the iron one goes to the tools chest.")
def _(c):
    c.chest("copper", 2, 10, [("stone_pickaxe", 1), ("iron_pickaxe", 1)], block="waxed_copper_chest")
    c.chest("labelled", 9, 10)
    c.frame(9, 10, "stone_pickaxe")
    c.chest("tools", 14, 10, [("wooden_axe", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "labelled", "stone_pickaxe", 1), expect(c, "labelled", "iron_pickaxe", 0),
                    expect(c, "tools", "iron_pickaxe", 1)]


@scenario("sign-plural", "core", 240, "A [Rail] sign means the rails group: powered rails and a minecart go there.")
def _(c):
    c.chest("copper", 2, 10, [("powered_rail", 8), ("minecart", 1)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("rails", 14, 10)
    c.sign(14, 10, "[Rail]")
    c.golem(4, 10)
    return lambda: [expect(c, "rails", "powered_rail", 8), expect(c, "rails", "minecart", 1), expect(c, "empty", "*", 0)]


@scenario("most-specific", "core", 270, "Stews go to [stew] (word match, 3 items) rather than [food]; bread goes to [food].")
def _(c):
    c.chest("copper", 2, 10, [("mushroom_stew", 1), ("mushroom_stew", 1), ("bread", 8)], block="waxed_copper_chest")
    c.chest("food", 8, 10)
    c.sign(8, 10, "[food]")
    c.chest("stew", 14, 10)
    c.sign(14, 10, "[stew]")
    c.golem(4, 10)
    return lambda: [expect(c, "stew", "mushroom_stew", 2), expect(c, "food", "mushroom_stew", 0), expect(c, "food", "bread", 8)]


@scenario("far-half-sign", "core", 120, "A sign on the far half of a double chest still labels the whole chest.")
def _(c):
    c.chest("copper", 2, 10, [("coal_ore", 8)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.double_chest("ores", 15, 10)
    c.sign(15, 10, "[ore]")  # on the left half, the one further from the golem
    c.golem(4, 10)
    return lambda: [expect(c, "ores", "coal_ore", 8), expect(c, "empty", "*", 0)]


@scenario("overflow-before-empty", "core", 120, "An item with no matching chest goes to [Overflow], not the nearer empty chest.")
def _(c):
    c.chest("copper", 2, 10, [("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("overflow", 14, 10)
    c.sign(14, 10, "[Overflow]")
    c.golem(4, 10)
    return lambda: [expect(c, "overflow", "sculk_shrieker", 1), expect(c, "empty", "*", 0)]


@scenario("full-chest-overflow", "core", 120, "When the matching log chest is full, birch logs go to [Overflow].")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("full", 8, 10, [("oak_log", 64)] * 27)
    c.chest("overflow", 14, 10)
    c.sign(14, 10, "[Overflow]")
    c.golem(4, 10)
    return lambda: [expect(c, "overflow", "birch_log", 16), expect(c, "full", "birch_log", 0)]


@scenario("return-homeless", "core", 420, "With nowhere to put it, the item goes back to its copper chest (after 120 active seconds) and stays there.")
def _(c):
    c.chest("copper", 2, 10, [("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("busy", 14, 10, [("dirt", 64)] * 27)  # a full, unrelated chest: no home, no empty chest
    c.golem(4, 10)
    picked_up = []  # must leave the copper chest first, or the check would pass at the start

    def check():
        in_chest, holding = c.count("copper", "sculk_shrieker"), c.holding()
        if in_chest == 0 and holding:
            picked_up.append(True)
        return [(bool(picked_up), "golem picked the item up" if picked_up else "golem hasn't picked the item up yet"),
                (in_chest == 1, f"copper chest has {in_chest} sculk_shrieker (want 1, returned)"),
                (holding == 0, f"golem holding {holding} items (want 0)")]
    return check


@scenario("fetch-from-overflow", "core", 150, "An idle golem fetches granite from [Overflow] into the stone chest.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")  # empty: nothing else to do
    c.chest("overflow", 8, 10, [("granite", 16)])
    c.sign(8, 10, "[Overflow]")
    c.chest("stone", 14, 10, [("andesite", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "stone", "granite", 16), expect(c, "overflow", "granite", 0)]


@scenario("sleepy-golem-sorts", "core", 300, "With no player nearby (Paper pauses idle golems), a golem still sorts birch logs to the log chest, just slower.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16), ("spruce_log", 16)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("logs", 14, 10, [("oak_log", 1)])
    c.golem(4, 10, awake=False)
    return lambda: [expect(c, "logs", "birch_log", 16), expect(c, "logs", "spruce_log", 16), expect(c, "empty", "*", 0)]


@scenario("sleepy-golem-return", "core", 600, "With no player nearby, a no-home item is still returned, after 120 seconds of the golem actually being active.")
def _(c):
    c.chest("copper", 2, 10, [("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("busy", 14, 10, [("dirt", 64)] * 27)
    c.golem(4, 10, awake=False)
    picked = []

    def check():
        in_chest, holding = c.count("copper", "sculk_shrieker"), c.holding()
        if in_chest == 0 and holding:
            picked.append(True)
        return [(bool(picked), "golem picked the item up"),
                (in_chest == 1, f"copper chest has {in_chest} sculk_shrieker (want 1, returned)"),
                (holding == 0, f"golem holding {holding} items (want 0)")]
    return check


@scenario("two-line-sign", "core", 300, "A sign reading [Wool] / [String] takes wool, carpet and string; string skips the nearer [mob drops] chest, a bone doesn't.")
def _(c):
    c.chest("copper", 2, 10, [("white_wool", 8), ("red_carpet", 4), ("string", 6), ("bone", 3)], block="waxed_copper_chest")
    c.chest("mob", 8, 10)
    c.sign(8, 10, "[mob drops]")
    c.chest("wool", 14, 10)
    c.sign(14, 10, ["[Wool]", "[String]"])
    c.golem(4, 10)
    return lambda: [expect(c, "wool", "white_wool", 8), expect(c, "wool", "red_carpet", 4),
                    expect(c, "wool", "string", 6), expect(c, "wool", "bone", 0),
                    expect(c, "mob", "bone", 3), expect(c, "mob", "string", 0)]
