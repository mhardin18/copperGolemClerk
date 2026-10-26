"""dimensions: golems sorting in the Nether and the End, not just the overworld.

The platforms sit at the usual spot (x=-500, y=150), which is above the Nether's
bedrock roof and out in the End's void, so nothing natural gets in the way.
"""
import re

from arena import expect, scenario
from suite_traffic import corridor

NETHER, END = "minecraft:the_nether", "minecraft:the_end"


@scenario("nether-group-match", "dimensions", 150, "In the Nether: birch logs go to the chest holding an oak log, past a nearer empty chest.", dimension=NETHER)
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("logs", 14, 10, [("oak_log", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "logs", "birch_log", 16), expect(c, "empty", "birch_log", 0)]


@scenario("end-sign-and-overflow", "dimensions", 200, "In the End: a [logs] sign takes birch logs; a sculk shrieker with no home goes to [Overflow], not the empty chest.", dimension=END)
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16), ("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("logs", 14, 10)
    c.sign(14, 10, "[logs]")
    c.chest("overflow", 8, 4)
    c.sign(8, 4, "[Overflow]")
    c.golem(4, 10)
    return lambda: [expect(c, "logs", "birch_log", 16), expect(c, "overflow", "sculk_shrieker", 1), expect(c, "empty", "*", 0)]


@scenario("nether-fetch-from-overflow", "dimensions", 150, "In the Nether: an idle golem fetches granite from [Overflow] into the stone chest.", dimension=NETHER)
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    c.chest("overflow", 8, 10, [("granite", 16)])
    c.sign(8, 10, "[Overflow]")
    c.chest("stone", 14, 10, [("andesite", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "stone", "granite", 16), expect(c, "overflow", "granite", 0)]


@scenario("end-return-homeless", "dimensions", 420, "In the End: with nowhere to put it, the item goes back to its copper chest after the timeouts.", dimension=END)
def _(c):
    c.chest("copper", 2, 10, [("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("busy", 14, 10, [("dirt", 64)] * 27)
    c.golem(4, 10)
    picked = []

    def check():
        in_chest, holding = c.count("copper", "sculk_shrieker"), c.holding()
        if in_chest == 0 and holding:
            picked.append(True)
        return [(bool(picked), "golem picked the item up"), (in_chest == 1, f"copper chest has {in_chest} (want 1, returned)"),
                (holding == 0, f"golem holding {holding} (want 0)")]
    return check


@scenario("nether-give-way", "dimensions", 420, "In the Nether: two golems meeting in a 1-wide corridor get past each other and every item arrives.", dimension=NETHER)
def _(c):
    return corridor(c)


@scenario("nether-check-command", "dimensions", 30, "/cgc check with the Nether's world name explains a chest there; the same spot in the overworld has no chest; a bad world name is reported.", dimension=NETHER)
def _(c):
    c.chest("logs", 10, 10, [("oak_log", 1)])
    x, y, z = c.abs(10, 10)
    nether = NETHER_WORLD

    def check():
        here = say(c, f"cgc check {x} {y} {z} {nether}")
        over = say(c, f"cgc check {x} {y} {z}")
        bad = say(c, f"cgc check {x} {y} {z} no_such_world")
        return [("plus anything in group logs" in here, f"Nether chest explained; got: {here.strip()[:200]}"),
                ("No container" in over or "isn't loaded" in over, f"overworld spot has no chest; got: {over.strip()[:120]}"),
                ("No world named" in bad, f"bad world name reported; got: {bad.strip()[:120]}")]
    return check


# Bukkit's names for pluginDev's Nether and End (the level name plus a suffix).
NETHER_WORLD = "pluginDev_world_nether"


def say(c, command):
    return re.sub(r"§.", "", c.r.cmd(command))
