"""reach: chests stacked above vanilla's reach (deliver-reach, take-reach).

Vanilla golems open chests up to 2 rows above the floor they stand on. With the
settings raised, golems walk to the foot of a taller stack and the plugin moves the
items for them. Rows count up from the floor: dy=0 is row 1, dy=3 is row 4.
"""
import re

from arena import expect, scenario
from suite_options import set_options


def tower(c, dx, dz, top_label, top_items=()):
    """Four chests stacked at (dx, dz): three labelled for other things, the top one (row 4) for top_label."""
    for dy, label in enumerate(["[stone]", "[wool]", "[sand]"]):
        c.chest(f"row{dy + 1}", dx, dz, dy=dy)
        c.sign(dx, dz, label, dy=dy)
    c.chest("top", dx, dz, list(top_items), dy=3)
    c.sign(dx, dz, top_label, dy=3)


def deliver_scene(c):
    c.chest("copper", 2, 10, [("birch_log", 8)], block="waxed_copper_chest")
    tower(c, 14, 10, "[logs]")
    c.chest("overflow", 8, 16)
    c.sign(8, 16, "[Overflow]")
    c.golem(4, 10)


@scenario("deliver-row4", "reach", 240, "deliver-reach: 4: birch logs go into the [logs] chest at the top of a 4-high stack.", alone=True)
def _(c):
    set_options(c, deliver_reach="4")
    deliver_scene(c)
    return lambda: [expect(c, "top", "birch_log", 8), expect(c, "overflow", "birch_log", 0)]


@scenario("deliver-row4-default", "reach", 240, "Default deliver-reach (2): the [logs] chest at the top of a 4-high stack is out of reach, so the logs go to [Overflow].")
def _(c):
    deliver_scene(c)
    return lambda: [expect(c, "overflow", "birch_log", 8), expect(c, "top", "birch_log", 0)]


@scenario("deliver-row4-reach3", "reach", 240, "deliver-reach: 3 doesn't reach row 4: the logs go to [Overflow].", alone=True)
def _(c):
    set_options(c, deliver_reach="3")
    deliver_scene(c)
    return lambda: [expect(c, "overflow", "birch_log", 8), expect(c, "top", "birch_log", 0)]


def take_scene(c):
    for dy in range(3):
        c.block(2, 10, "stone_bricks", dy=dy)
    c.chest("copper", 2, 10, [("birch_log", 8)], block="waxed_copper_chest", dy=3)
    c.chest("logs", 14, 10, [("oak_log", 1)])
    c.golem(5, 10)


@scenario("take-row4", "reach", 240, "take-reach: 4: a golem takes birch logs from a copper chest on a 3-block pillar (row 4) and sorts them.", alone=True)
def _(c):
    set_options(c, take_reach="4")
    take_scene(c)
    return lambda: [expect(c, "logs", "birch_log", 8), expect(c, "copper", "birch_log", 0)]


@scenario("take-row4-default", "reach", 90, "Default take-reach (2): the copper chest on a 3-block pillar is out of reach; its logs stay put.")
def _(c):
    take_scene(c)
    return lambda: [(c.elapsed() > 60, "waited a minute"), expect(c, "copper", "birch_log", 8),
                    expect(c, "logs", "birch_log", 0)]


def say(c, command):
    return re.sub(r"§.", "", c.r.cmd(command))


@scenario("check-reach", "reach", 30, "/cgc check on the top of a 4-high stack says it's 4 rows up and out of reach at the default setting.")
def _(c):
    tower(c, 14, 10, "[logs]")
    x, y, z = c.abs(14, 10, 3)
    reply = lambda: say(c, f"cgc check {x} {y} {z}")
    return lambda: [("4 chest rows up" in reply() and "raise it to 4" in reply(), f"says 4 rows up, out of reach; got: {reply().strip()}")]


# ---- edge cases

from arena import Trigger, conserved  # noqa: E402


def heading_high(c):
    return any("heads for the chest" in l and c.in_cell(l) for l in c.log_lines())


@scenario("deliver-high-walled-off", "reach", 300, "deliver-reach: 4, but the floor in front of the [logs] stack is walled off: the golem gives up on it and the logs go to [Overflow], none lost.", alone=True)
def _(c):
    set_options(c, deliver_reach="4", matching_chest_timeout_seconds="20")
    deliver_scene(c)
    # A glass ring two blocks out and five high around the stack, with no roof (a block on
    # top of the chest would stop it opening at all): the floor beside it can't be reached.
    x, y, z = c.abs(14, 10)
    for x1, z1, x2, z2 in ((x - 2, z - 2, x + 2, z - 2), (x - 2, z + 2, x + 2, z + 2),
                           (x - 2, z - 2, x - 2, z + 2), (x + 2, z - 2, x + 2, z + 2)):
        c.run(f"fill {x1} {y} {z1} {x2} {y + 4} {z2} minecraft:glass")
    def check():
        tried = any("heads for the chest" in l and c.in_cell(l) for l in c.log_lines())
        return [(tried, "the golem tried the high chest first"), expect(c, "overflow", "birch_log", 8),
                expect(c, "top", "birch_log", 0), conserved(c, "birch_log", 8)]
    return check


@scenario("deliver-high-chest-broken", "reach", 240, "deliver-reach: 4: the top chest is broken while the golem walks to it; the logs go to [Overflow] instead, none lost.", alone=True)
def _(c):
    set_options(c, deliver_reach="4")
    deliver_scene(c)
    t = Trigger()
    top = c.chests["top"][0]
    def check():
        t.fire_when(lambda: heading_high(c), lambda: c.r.cmd(f"setblock {top[0]} {top[1]} {top[2]} minecraft:air"))
        return [(t.at is not None, "the top chest was broken mid-walk"), expect(c, "overflow", "birch_log", 8)]
    return check


@scenario("deliver-high-no-golems", "reach", 240, "deliver-reach: 4, but the top chest also says [No Golems]: the logs go to [Overflow].", alone=True)
def _(c):
    set_options(c, deliver_reach="4")
    deliver_scene(c)
    c.sign(14, 10, ["[logs]", "[No Golems]"], dy=3)
    return lambda: [expect(c, "overflow", "birch_log", 8), expect(c, "top", "birch_log", 0)]


@scenario("deliver-high-full", "reach", 240, "deliver-reach: 4, but the top [logs] chest is full: the logs go to [Overflow].", alone=True)
def _(c):
    set_options(c, deliver_reach="4")
    c.chest("copper", 2, 10, [("birch_log", 8)], block="waxed_copper_chest")
    tower(c, 14, 10, "[logs]", top_items=[("dirt", 64)] * 27)
    c.chest("overflow", 8, 16)
    c.sign(8, 16, "[Overflow]")
    c.golem(4, 10)
    return lambda: [expect(c, "overflow", "birch_log", 8), expect(c, "top", "birch_log", 0)]


@scenario("deliver-high-two-golems", "reach", 300, "deliver-reach: 4: two golems both deliver to the top of the stack; all 32 logs arrive.", alone=True)
def _(c):
    set_options(c, deliver_reach="4")
    c.chest("copper", 2, 10, [("birch_log", 16), ("spruce_log", 16)], block="waxed_copper_chest")
    tower(c, 14, 10, "[logs]")
    c.chest("overflow", 8, 18)
    c.sign(8, 18, "[Overflow]")
    c.golem(4, 8)
    c.golem(4, 12)
    return lambda: [expect(c, "top", "birch_log", 16), expect(c, "top", "spruce_log", 16)]


@scenario("deliver-reach-lowered-midway", "reach", 300, "deliver-reach drops from 4 to 2 (reload) while a golem walks to the top chest: it ends up using [Overflow], nothing lost or stuck.", alone=True)
def _(c):
    set_options(c, deliver_reach="4", matching_chest_timeout_seconds="20")
    deliver_scene(c)
    t = Trigger()
    def check():
        t.fire_when(lambda: heading_high(c), lambda: set_options(c, deliver_reach="2"))
        return [(t.at is not None, "reach lowered mid-walk"), expect(c, "overflow", "birch_log", 8),
                conserved(c, "birch_log", 8)]
    return check


@scenario("take-high-homeless", "reach", 300, "take-reach: 4: a high copper chest holds only dirt, which has no home: the golem takes it, returns it, and doesn't keep fetching it.", alone=True)
def _(c):
    set_options(c, take_reach="4", matching_chest_timeout_seconds="10", allow_empty_chests="false")
    for dy in range(3):
        c.block(2, 10, "stone_bricks", dy=dy)
    c.chest("copper", 2, 10, [("dirt", 8)], block="waxed_copper_chest", dy=3)
    c.chest("logs", 14, 10, [("oak_log", 1)])
    c.golem(5, 10)
    def check():
        takes = [l for l in c.log_lines() if "took" in l and "DIRT" in l and c.in_cell(l)]
        returned = [l for l in c.log_lines() if "returned DIRT" in l and c.in_cell(l)]
        return [(len(returned) >= 1, "the dirt was returned"), (c.elapsed() > 150, "waited 150 seconds"),
                (len(takes) <= 2, f"taken at most twice (got {len(takes)})"), conserved(c, "dirt", 8)]
    return check
