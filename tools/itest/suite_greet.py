"""greet: idle copper golems say hello to iron and snow golems (greet-other-golems).

Reads the plugin's debug lines ("going to say hello", "said hello"), so pluginDev must
run with debug: true.
"""
from arena import Trigger, expect, scenario
from suite_modes import golem
from suite_options import set_options


def boxed(c, kind, dx, dz):
    """An iron or snow golem kept in place by a fence ring (copper golems can still get next to it)."""
    x, y, z = c.abs(dx, dz)
    c.run(f"fill {x - 1} {y} {z - 1} {x + 1} {y} {z + 1} minecraft:oak_fence hollow")
    c.run(f"summon minecraft:{kind} {x + 0.5} {y} {z + 0.5} {{Tags:[\"itest_other\"]}}")


def hellos(c, text="said hello"):
    return [l for l in c.log_lines() if text in l and c.in_cell(l)]


@scenario("greet-iron-golem", "greet", 90, "An idle copper golem walks over to an iron golem and says hello.")
def _(c):
    boxed(c, "iron_golem", 14, 10)
    golem(c, 4, 10)
    return lambda: [(len(hellos(c)) >= 1, "said hello to the iron golem"),
                    (any("iron golem" in l for l in hellos(c)), "to the iron golem")]


@scenario("greet-snow-golem", "greet", 90, "An idle copper golem says hello to a snow golem too.")
def _(c):
    boxed(c, "snow_golem", 14, 10)
    golem(c, 4, 10)
    return lambda: [(any("snow golem" in l for l in hellos(c)), "said hello to the snow golem")]


@scenario("greet-cooldown", "greet", 75, "After saying hello, the golem goes back to its routine and doesn't greet again during the (default 5-minute) cooldown.")
def _(c):
    boxed(c, "iron_golem", 14, 10)
    golem(c, 4, 10)
    first = Trigger()
    def check():
        first.fire_when(lambda: len(hellos(c)) >= 1, lambda: None)
        if first.at is None:
            return [(False, "waiting for the first hello")]
        return [(first.since() > 45, "45 seconds since the hello"),
                (len(hellos(c)) == 1, f"only one hello (got {len(hellos(c))})")]
    return check


@scenario("greet-again-after-cooldown", "greet", 120, "greet-cooldown-minutes: 0.25: the golem says hello again once the 15-second cooldown is over.", alone=True)
def _(c):
    set_options(c, greet_cooldown_minutes="0.25")
    boxed(c, "iron_golem", 14, 10)
    golem(c, 4, 10)
    return lambda: [(len(hellos(c)) >= 2, f"two hellos (got {len(hellos(c))})")]


@scenario("greet-not-while-sorting", "greet", 180, "A golem with logs to sort doesn't go visiting until the copper chest is empty.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16), ("spruce_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 14, 4, [("oak_log", 1)])
    boxed(c, "iron_golem", 14, 16)
    golem(c, 4, 10)
    early = Trigger()
    def check():
        early.fire_when(lambda: c.count("copper") > 0 and hellos(c, "going to say hello"), lambda: None)
        return [(early.at is None, "no visit while the copper chest still had logs"),
                expect(c, "logs", "birch_log", 16), expect(c, "logs", "spruce_log", 16)]
    return check


@scenario("greet-off", "greet", 45, "greet-other-golems: false: an idle copper golem ignores the iron golem.", alone=True)
def _(c):
    set_options(c, greet_other_golems="false")
    boxed(c, "iron_golem", 14, 10)
    golem(c, 4, 10)
    return lambda: [(c.elapsed() > 30, "waited 30 seconds"), (not hellos(c, "say hello"), "no visit")]


@scenario("greet-not-vanilla-mode", "greet", 45, "A golem switched to vanilla doesn't go visiting.")
def _(c):
    boxed(c, "iron_golem", 14, 10)
    golem(c, 4, 10, vanilla=True)
    return lambda: [(c.elapsed() > 30, "waited 30 seconds"), (not hellos(c, "say hello"), "no visit")]


@scenario("greet-other-dies-on-the-way", "greet", 60, "The iron golem is killed while the copper golem walks over: the visit ends quietly, no errors.")
def _(c):
    x, y, z = c.abs(18, 18)
    c.run(f"summon minecraft:iron_golem {x + 0.5} {y} {z + 0.5} {{Tags:[\"itest_other\"],NoAI:1b}}")
    golem(c, 2, 2)
    killed = Trigger()
    def check():
        killed.fire_when(lambda: hellos(c, "going to say hello"), lambda: c.r.cmd(f"kill {c.selector('iron_golem')}"))  # this platform's only
        return [(killed.at is not None and killed.since() > 10, "visit started, golem killed, 10 seconds passed"),
                (not hellos(c), "no hello to a dead golem")]
    return check


@scenario("greet-unreachable", "greet", 60, "An iron golem sealed in a glass box: the copper golem never gets to say hello and isn't left walking at it forever.")
def _(c):
    x, y, z = c.abs(14, 10)
    c.run(f"fill {x - 2} {y} {z - 2} {x + 2} {y + 4} {z + 2} minecraft:glass hollow")
    c.run(f"fill {x - 1} {y} {z - 1} {x + 1} {y + 3} {z + 1} minecraft:air")
    c.run(f"summon minecraft:iron_golem {x + 0.5} {y} {z + 0.5}")
    golem(c, 4, 10)
    def check():
        visits = hellos(c, "going to say hello")
        gave_up = hellos(c, "to say hello; giving up")
        return [(c.elapsed() > 45, "waited 45 seconds"), (not hellos(c), "no hello through the glass"),
                (not visits or gave_up, "any visit was given up")]
    return check


@scenario("greet-crowd", "greet", 90, "Three idle copper golems and one iron golem: each says hello once, then waits out its cooldown.")
def _(c):
    boxed(c, "iron_golem", 14, 10)
    for dz in (4, 10, 16):
        golem(c, 4, dz)
    def check():
        n = len(hellos(c))
        return [(c.elapsed() > 60, "waited a minute"), (n == 3, f"three hellos (got {n})")]
    return check


@scenario("greet-interrupted-by-work", "greet", 120, "Logs turn up in the copper chest while the golem walks over to say hello: it drops the visit and sorts them.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    c.chest("logs", 2, 16, [("oak_log", 1)])
    x, y, z = c.abs(20, 2)
    c.run(f"summon minecraft:iron_golem {x + 0.5} {y} {z + 0.5} {{NoAI:1b}}")
    golem(c, 4, 10)
    t = Trigger()
    sorted_ = Trigger()
    early = Trigger()
    def stock():
        cx, cy, cz = c.chests["copper"][0]
        c.r.cmd(f"item replace block {cx} {cy} {cz} container.0 with minecraft:birch_log 8")
    def check():
        t.fire_when(lambda: hellos(c, "going to say hello"), stock)
        sorted_.fire_when(lambda: t.at is not None and c.count("logs", "birch_log") == 8, lambda: None)
        # A hello once the logs are sorted is fine (the golem is idle again); one before isn't.
        early.fire_when(lambda: t.at is not None and sorted_.at is None and hellos(c), lambda: None)
        return [(t.at is not None, "a visit started, then logs were added"),
                (sorted_.at is not None, "the logs were sorted"), (early.at is None, "no hello before the logs were sorted")]
    return check
