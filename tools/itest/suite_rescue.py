"""rescue: lost golems walk back to their copper chest (rescue-lost-golems).

A golem's home is the copper chest it last took items from, kept on the golem as
coppergolemclerk:home = "<world>;x;y;z". Most scenarios set it directly, then put the
golem out on a walkway east of the platform, beyond 40 blocks from the chest.
"""
import re

from arena import Y, Trigger, _count, scenario
from suite_modes import LUCK
from suite_options import set_options

WORLD = "pluginDev_world"
FAR = 50  # dx of the lost golem: 48 blocks east of the copper chest at dx=2


def home_nbt(c, dx, dz, dy=0):
    x, y, z = c.abs(dx, dz, dy)
    return f'BukkitValues:{{"coppergolemclerk:home":"{WORLD};{x};{y};{z}"}}'


def lost_golem(c, dx, dz, home=(2, 10), name=None, extra=""):
    x, y, z = c.abs(dx, dz)
    tag = f',CustomName:"{name}"' if name else ""
    c.run(f"summon minecraft:copper_golem {x + 0.5} {y} {z + 0.5} "
          f"{{active_effects:[{LUCK}],{home_nbt(c, *home)}{tag}{extra}}}")


def walkway(c, to=FAR + 4):
    """A 3-wide floor from the platform's east wall out to dx=to, with a gap in the wall."""
    x, y, z = c.abs(0, 10)
    c.run(f"fill {x + 21} {Y} {z - 1} {x + 21} {Y + 2} {z + 1} minecraft:air")
    c.run(f"fill {x + 21} {Y - 1} {z - 1} {x + to} {Y - 1} {z + 1} minecraft:glowstone")
    c.cleanup += [f"kill @e[type=minecraft:copper_golem,x={x + 20},y={Y - 5},z={z - 3},dx={to - 18},dy=12,dz=6]",
                  f"fill {x + 21} {Y - 1} {z - 1} {x + to} {Y + 3} {z + 1} minecraft:air"]


def far_golems(c):
    x, y, z = c.abs(0, 10)
    return _count(c.r.cmd(f"execute if entity @e[type=minecraft:copper_golem,x={x + 40},y={Y - 5},z={z - 3},dx=30,dy=12,dz=6]"))


def logged(c, text):
    return [l for l in c.log_lines() if text in l and c.in_cell(l)]


@scenario("rescue-home-learned", "rescue", 90, "A golem that takes logs from a copper chest remembers it as its home.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 8)], block="waxed_copper_chest")
    c.chest("logs", 10, 10)
    c.sign(10, 10, "[logs]")
    c.golem(4, 10)
    x, y, z = c.abs(2, 10)

    def check():
        data = c.r.cmd(f"data get entity {c.selector('copper_golem')[:-1]},limit=1] BukkitValues")
        return [(f"{WORLD};{x};{y};{z}" in data, f"home is the copper chest at {x},{y},{z}: {data.strip()[-90:]}")]
    return check


@scenario("rescue-walks-back", "rescue", 150, "A golem 48 blocks from its copper chest, out of range of any copper chest, walks back until it's in range again.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    walkway(c)
    lost_golem(c, FAR, 10)
    return lambda: [(bool(logged(c, "is lost")), "logged that it's lost and walking back"),
                    (bool(logged(c, "is back near its copper chest")), "logged that it's back"),
                    (far_golems(c) == 0, "no golem left out on the walkway")]


@scenario("rescue-gives-up-alert", "rescue", 150, "A lost golem boxed in out on the walkway can't get closer: an alert says where it is; /cgc golems lists it as lost.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    walkway(c)
    x, y, z = c.abs(FAR, 10)
    c.run(f"fill {x - 1} {y} {z - 1} {x + 1} {y + 2} {z + 1} minecraft:glass hollow")
    c.run(f"setblock {x} {y} {z} minecraft:air")
    lost_golem(c, FAR, 10, name="itest-rescue-boxed")

    def check():
        alerts = [l for l in c.log_lines() if "Alert" in l and "itest-rescue-boxed" in l]
        listed = re.sub(r"§.", "", c.r.cmd(f"cgc golems {x} {y} {z}"))
        line = next((l for l in re.split(r"(?=\d+\. )", listed) if "itest-rescue-boxed" in l), "")
        return [(bool(alerts), "an alert names the golem"),
                (any("can't find a way back" in l for l in alerts), "says it can't find a way back"),
                (any(f"{x}, {y}, {z}" in l for l in alerts), f"says where it is ({x}, {y}, {z})"),
                ("lost at" in line, f"/cgc golems lists it as lost: {line.strip()!r}")]
    return check


@scenario("rescue-teleport-home", "rescue", 150, "lost-golem-teleport-home: true: the boxed-in lost golem is teleported back beside its copper chest.", alone=True)
def _(c):
    set_options(c, lost_golem_teleport_home="true")
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    walkway(c)
    x, y, z = c.abs(FAR, 10)
    c.run(f"fill {x - 1} {y} {z - 1} {x + 1} {y + 2} {z + 1} minecraft:glass hollow")
    c.run(f"setblock {x} {y} {z} minecraft:air")
    lost_golem(c, FAR, 10)
    return lambda: [(any("teleported it back" in l for l in c.log_lines() if "Alert" in l), "alert says it was teleported back"),
                    (far_golems(c) == 0, "the golem left the box"), (c.golems() == 1, "it's on the platform")]


@scenario("rescue-stuck-in-pit", "rescue", 240, "A golem walled in 8 blocks from its copper chest (in range, no way out) is reported as stuck after two minutes.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    x, y, z = c.abs(12, 10)
    c.run(f"fill {x - 1} {y} {z - 1} {x + 1} {y + 2} {z + 1} minecraft:glass hollow")
    c.run(f"setblock {x} {y} {z} minecraft:air")
    lost_golem(c, 12, 10, name="itest-rescue-pit")
    return lambda: [(any("is stuck at" in l and "itest-rescue-pit" in l for l in c.log_lines() if "Alert" in l),
                     "an alert says it's stuck")]


@scenario("rescue-adopts-new-home", "rescue", 45, "A golem whose home is far away, standing by another copper chest, adopts that chest as home and isn't treated as lost.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    x, y, z = c.abs(2, 10)
    c.run(f"summon minecraft:copper_golem {x + 2.5} {y} {z + 0.5} "
          f'{{active_effects:[{LUCK}],BukkitValues:{{"coppergolemclerk:home":"{WORLD};{x + 3000};{y};{z}"}}}}')
    t = Trigger()

    def check():
        data = c.r.cmd(f"data get entity {c.selector('copper_golem')[:-1]},limit=1] BukkitValues")
        adopted = f"{WORLD};{x};{y};{z}" in data
        t.fire_when(lambda: adopted, lambda: None)
        return [(adopted, f"home is now the chest at {x},{y},{z}"), (t.since() > 12, "waited 12s more"),
                (not logged(c, "is lost") and not any("Alert" in l and "lost" in l for l in c.log_lines() if c.in_cell(l)),
                 "never treated as lost")]
    return check


@scenario("rescue-home-chest-gone", "rescue", 45, "A golem whose copper chest was broken forgets its home instead of trying to walk to it.")
def _(c):
    walkway(c)
    lost_golem(c, FAR, 10, extra=",NoAI:1b")  # no wandering off the walkway

    def check():
        x, y, z = c.abs(0, 10)
        data = c.r.cmd(f"data get entity @e[type=minecraft:copper_golem,x={x + 34},y={Y - 5},z={z - 3},dx=40,dy=12,dz=6,limit=1] BukkitValues")
        return [(far_golems(c) == 1, "golem still out on the walkway"),
                ("coppergolemclerk:home" not in data, "its home was forgotten"),
                (not logged(c, "is lost"), "not walked anywhere")]
    return check


@scenario("option-rescue-off", "rescue", 60, "rescue-lost-golems: false: a lost golem is left where it is.", alone=True)
def _(c):
    set_options(c, rescue_lost_golems="false")
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    walkway(c)
    lost_golem(c, FAR, 10)
    t = Trigger()

    def check():
        t.fire_when(lambda: True, lambda: None)
        return [(t.since() > 30, "waited 30s"), (not logged(c, "is lost"), "not walked back"),
                (far_golems(c) == 1, "still out on the walkway")]
    return check
