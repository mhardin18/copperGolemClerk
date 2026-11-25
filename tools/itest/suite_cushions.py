"""cushions: idle copper golems sit on cushions (sit-on-cushions), and get up again.

Reads the plugin's debug lines ("sat down on the cushion", "got up from its cushion",
"said hello"), so pluginDev must run with debug: true (run.py turns it on).
"""
from arena import Trigger, _count, expect, scenario
from suite_greet import boxed, hellos
from suite_modes import golem
from suite_options import set_options


def cushion(c, dx, dz, color="white"):
    x, y, z = c.abs(dx, dz)
    c.run(f'summon minecraft:cushion {x + 0.5} {y} {z + 0.5} {{color:"{color}"}}')


def seated(c):
    """How many copper golems on the platform are sitting on a cushion."""
    return _count(c.r.cmd(f"execute as {c.selector('copper_golem')} on vehicle if entity @s[type=minecraft:cushion]"))


def sat_down(c):
    return [l for l in c.cell_lines() if "sat down on the cushion" in l]


def got_up(c, why=""):
    return [l for l in c.cell_lines() if "got up from its cushion" in l and why in l]


@scenario("cushion-sit", "cushions", 90, "An idle copper golem walks to a cushion 6 blocks away and sits on it.")
def _(c):
    cushion(c, 10, 10)
    golem(c, 4, 10)
    return lambda: [(len(sat_down(c)) >= 1, "sat down (debug line)"), (seated(c) == 1, f"seated: {seated(c)}")]


@scenario("cushion-one-golem-per-cushion", "cushions", 100, "Three idle golems and two cushions: two sit, one on each, and the third stays standing.")
def _(c):
    cushion(c, 10, 8)
    cushion(c, 10, 12, "orange")
    for dz in (6, 10, 14):
        golem(c, 4, dz)
    def check():
        return [(seated(c) == 2, f"seated: {seated(c)} (want 2)"),
                (c.elapsed() > 60, "still two after a minute")]
    return check


@scenario("cushion-up-for-work", "cushions", 200, "A seated golem gets up when logs land in its copper chest, sorts them, and sits down again afterwards.")
def _(c):
    cushion(c, 10, 10)
    c.chest("copper", 4, 12, block="waxed_copper_chest")
    c.chest("logs", 14, 4, [("oak_log", 1)])
    golem(c, 6, 10)
    stage = {}
    def check():
        if "logs" not in stage:
            if seated(c) != 1:
                return [(False, "waiting for the golem to sit down")]
            x, y, z = c.abs(4, 12)
            c.run(f"item replace block {x} {y} {z} container.0 with minecraft:birch_log 16")
            stage["logs"] = len(sat_down(c))
        return [(len(got_up(c, "there's work to do")) >= 1, "got up for the logs"),
                expect(c, "logs", "birch_log", 16),
                (len(sat_down(c)) > stage["logs"], "sat down again once the work was done")]
    return check


@scenario("cushion-greet-first", "cushions", 120, "With an iron golem and a cushion both nearby, the golem says hello first, then sits down.")
def _(c):
    boxed(c, "iron_golem", 14, 14)
    cushion(c, 10, 6)
    golem(c, 4, 10)
    def check():
        hello, sat = hellos(c), sat_down(c)
        order = bool(hello and sat) and c.cell_lines().index(hello[0]) < c.cell_lines().index(sat[0])
        return [(len(hello) >= 1, "said hello"), (len(sat) >= 1, "sat down"),
                (order, "the hello came before sitting down")]
    return check


@scenario("cushion-up-to-greet", "cushions", 150, "greet-cooldown-minutes: 0.5: a seated golem gets up to say hello again once its cooldown is over.", alone=True)
def _(c):
    set_options(c, greet_cooldown_minutes="0.5")
    boxed(c, "iron_golem", 14, 14)
    cushion(c, 10, 6)
    golem(c, 4, 10)
    stage = {}
    def check():
        if "sat" not in stage:
            if not (hellos(c) and seated(c) == 1):
                return [(False, "waiting for a hello and then sitting down")]
            stage["sat"] = len(hellos(c))
        return [(len(hellos(c)) > stage["sat"], "got up and said hello again")]
    return check


@scenario("cushion-off", "cushions", 50, "sit-on-cushions: false: an idle golem never sits down.", alone=True)
def _(c):
    set_options(c, sit_on_cushions="false")
    cushion(c, 10, 10)
    golem(c, 4, 10)
    early = Trigger()
    def check():
        early.fire_when(lambda: seated(c) > 0 or sat_down(c), lambda: None)
        return [(early.at is None, "never sat down"), (c.elapsed() > 40, "40 seconds")]
    return check


@scenario("cushion-turned-off-stands-up", "cushions", 120, "Turning sit-on-cushions off gets a seated golem up; one put on a cushion with /ride stays.", alone=True)
def _(c):
    cushion(c, 10, 10)
    golem(c, 4, 10)
    cushion(c, 16, 16, "orange")
    x, y, z = c.abs(16, 16)
    c.run(f"summon minecraft:copper_golem {x + 0.5} {y} {z + 0.5} {{NoAI:1b,Tags:[\"itest_rider\"]}}")
    c.run(f"ride @e[type=minecraft:copper_golem,tag=itest_rider,limit=1] mount "
          f"@e[type=minecraft:cushion,x={x + 0.5},y={y},z={z + 0.5},distance=..1,limit=1]")
    stage = {}
    def check():
        if "off" not in stage:
            if seated(c) != 2:
                return [(False, f"waiting for the golem to sit down (seated: {seated(c)})")]
            set_options(c, sit_on_cushions="false")
            stage["off"] = True
        return [(len(got_up(c, "sit-on-cushions is off")) >= 1, "the plugin's golem got up"),
                (seated(c) == 1, f"seated: {seated(c)} (the /ride golem stays)")]
    return check
