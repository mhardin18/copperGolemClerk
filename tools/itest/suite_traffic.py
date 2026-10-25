"""traffic: golems meeting head-on give way (golems-give-way).

Layout: two rooms joined by a corridor one block wide along z=10 (x 6..14).
West room: copper chest of birch logs, a [stone] chest, golem A. East room: copper
chest of granite, a [logs] chest, golem B. Each golem carries its item through the
corridor toward the other, so they meet inside it, over and over until both copper
chests are empty.
"""
from arena import Y, Trigger, _count, conserved, expect, scenario
from suite_options import set_options


def corridor(c, niche=False, floor=None, width=1, x1=6, x2=14):
    x, z = c.x, c.z
    for z1, z2 in ((0, 9), (10 + width, 20)):  # solid glass either side of the corridor
        c.run(f"fill {x + x1} {Y} {z + z1} {x + x2} {Y + 2} {z + z2} minecraft:glass")
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
    c.golem(min(3, x1 - 1), 10)
    c.golem(max(17, x2 + 1), 10)
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


MARKED = '{BukkitValues:{"smartcoppergolems:giving-way":1b}}'
UNAWARE = '{"Bukkit.Aware":0b}'


def golems_with(c, nbt, negate=False):
    sel = c.selector("copper_golem")[:-1] + f",nbt={'!' if negate else ''}{nbt}]"
    return _count(c.r.cmd(f"execute if entity {sel}"))


def frozen(c):
    """Golems paused for giving way (marked) or left with their AI off."""
    return golems_with(c, MARKED) + golems_with(c, UNAWARE)


@scenario("give-way-reload-off", "traffic", 420, "golems-give-way is switched off (reload) while a golem is stepping aside: it's woken at once, none left frozen.", alone=True)
def _(c):
    corridor(c)
    t = Trigger()

    def check():
        t.fire_when(lambda: golems_with(c, MARKED) > 0, lambda: set_options(c, golems_give_way="false"))
        if t.at is None:
            return [(False, "waiting for a golem to start giving way")]
        stuck = frozen(c)
        return [(True, "turned give-way off mid-sidestep"), (t.since() > 3, "waited 3s"),
                (stuck == 0, f"{stuck} golems still paused (want 0)")]
    return check


@scenario("give-way-unload", "traffic", 420, "The chunks unload while a golem is stepping aside, then load again: it's woken up and sorting finishes.")
def _(c):
    done = corridor(c)
    unload, reload = Trigger(), Trigger()

    def check():
        unload.fire_when(lambda: golems_with(c, MARKED) > 0, lambda: c.forceload(False))
        reload.fire_when(lambda: unload.since() >= 10, lambda: c.forceload(True))
        if reload.at is None:
            return [(False, "waiting to unload mid-sidestep, then reload")]
        stuck = frozen(c) if reload.since() > 5 else -1
        return [(stuck == 0, f"{stuck} golems still paused 5s after reloading (want 0)")] + done()
    return check


@scenario("give-way-other-killed", "traffic", 420, "The golem being made way for is killed mid-sidestep: the one stepping aside is woken, no errors.")
def _(c):
    corridor(c)
    t = Trigger()

    def check():
        t.fire_when(lambda: golems_with(c, MARKED) > 0,
                    lambda: c.r.cmd(f"kill {c.selector('copper_golem')[:-1]},nbt=!{MARKED}]"))
        if t.at is None:
            return [(False, "waiting for a golem to start giving way")]
        stuck = frozen(c)
        return [(True, "killed the other golem mid-sidestep"), (t.since() > 5, "waited 5s"),
                (stuck == 0, f"{stuck} golems still paused (want 0)"), (c.golems() == 1, f"{c.golems()} golem left (want 1)")]
    return check


@scenario("give-way-three", "traffic", 480, "Three golems share the 1-wide corridor (two start west, one east): nobody stays stuck and every item arrives.")
def _(c):
    done = corridor(c)
    c.golem(2, 11)
    return done


@scenario("give-way-wide", "traffic", 420, "A corridor two blocks wide: golems meeting head-on get past each other and every item arrives.")
def _(c):
    return corridor(c, width=2)


@scenario("give-way-doorway", "traffic", 420, "Two rooms joined by a single doorway in a wall: golems meeting in the doorway get through and every item arrives.")
def _(c):
    return corridor(c, x1=10, x2=10)


@scenario("give-way-long-corridor", "traffic", 480, "A bare 1-wide corridor 15 blocks long between two small rooms: a golem backs all the way out if it has to; every item arrives.")
def _(c):
    return corridor(c, x1=3, x2=17)
