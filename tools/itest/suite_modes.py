"""modes: golems a player switched to vanilla (right-click with golem-toggle-item).

The right-click itself needs a real player, so these scenarios set the mode the way the
plugin stores it: coppergolemclerk:mode = "vanilla" in the golem's persistent data.
"""
from arena import _count, expect, scenario

LUCK = '{id:"minecraft:luck",duration:-1,show_particles:0b,show_icon:0b}'
VANILLA = 'BukkitValues:{"coppergolemclerk:mode":"vanilla"}'


def golem(c, dx, dz, vanilla=False):
    x, y, z = c.abs(dx, dz)
    extra = "," + VANILLA if vanilla else ""
    c.run(f"summon minecraft:copper_golem {x + 0.5} {y} {z + 0.5} {{active_effects:[{LUCK}]{extra}}}")


def markers(c):
    return _count(c.r.cmd(f"execute if entity {c.selector('item_display')}"))


@scenario("vanilla-mode-sorts-like-vanilla", "modes", 120, "A golem switched to vanilla puts birch logs into the nearest empty chest, not the oak log chest a plugin golem would use.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 8)], block="waxed_copper_chest")
    c.chest("empty", 6, 14)
    c.chest("logs", 14, 10, [("oak_log", 1)])
    golem(c, 4, 10, vanilla=True)
    return lambda: [expect(c, "empty", "birch_log", 8), expect(c, "logs", "birch_log", 0)]


@scenario("mode-markers", "modes", 30, "A golem using the plugin gets the green antenna marker; one switched to vanilla doesn't.")
def _(c):
    golem(c, 4, 6)
    golem(c, 4, 14, vanilla=True)
    return lambda: [(markers(c) == 1, f"one marker (got {markers(c)})")]


@scenario("mode-marker-follows-switch", "modes", 30, "Switching a golem to vanilla removes its marker; the marker goes when the golem dies.")
def _(c):
    golem(c, 4, 10)
    sel = c.selector("copper_golem")[:-1] + ",limit=1]"
    state = {"step": 0}
    def check():
        if state["step"] == 0:
            if markers(c) != 1:
                return [(False, "waiting for the marker")]
            c.run(f"data merge entity {sel} {{{VANILLA}}}")
            state["step"] = 1
        if state["step"] == 1:
            if markers(c) != 0:
                return [(False, "waiting for the marker to go after switching to vanilla")]
            # /data remove doesn't reach Paper's plugin data; any value but "vanilla" means plugin mode.
            c.run(f'data merge entity {sel} {{BukkitValues:{{"coppergolemclerk:mode":"plugin"}}}}')
            state["step"] = 2
        if state["step"] == 2:
            if markers(c) != 1:
                return [(False, "waiting for the marker to come back after switching back")]
            c.run(f"kill {sel}")
            state["step"] = 3
        return [(markers(c) == 0, f"marker gone with the golem (got {markers(c)})")]
    return check


# ---- only copper golems: iron and snow golems are left alone

def other_golems(c):
    """An iron golem and a snow golem, each boxed in glass so they stay on the platform."""
    for kind, dx in (("iron_golem", 6), ("snow_golem", 12)):
        x, y, z = c.abs(dx, 10)
        c.run(f"fill {x - 1} {y} {z - 1} {x + 1} {y + 3} {z + 1} minecraft:glass hollow")
        c.run(f"summon minecraft:{kind} {x + 0.5} {y} {z + 0.5} {{Tags:[\"itest_other\"]}}")


@scenario("other-golems-unmarked", "modes", 30, "Iron and snow golems never get the antenna marker; a copper golem beside them does.")
def _(c):
    other_golems(c)
    golem(c, 16, 4)
    return lambda: [(c.elapsed() > 6, "waited 6 seconds"), (markers(c) == 1, f"only the copper golem's marker (got {markers(c)})")]


@scenario("other-golems-no-death-log", "modes", 30, "Iron and snow golems dying isn't logged by the plugin; a copper golem dying is.")
def _(c):
    other_golems(c)
    golem(c, 16, 4)
    c.run(f"kill {c.selector('iron_golem')}")  # this platform's only
    c.run(f"kill {c.selector('snow_golem')}")
    c.run(f"kill {c.selector('copper_golem')}")
    def check():
        lines = [l for l in c.log_lines() if "[CopperGolemClerk]" in l and c.in_cell(l)]
        died = [l for l in lines if "died at" in l]
        return [(len(died) == 1, f"one death logged, the copper golem's (got {len(died)})"),
                (not any("snow" in l.lower() or "iron" in l.lower() for l in lines), "nothing about iron or snow golems")]
    return check


@scenario("other-golems-ignored-by-sorting", "modes", 120, "An iron golem standing among the chests doesn't change where a copper golem sorts, and the plugin logs nothing about it.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 8)], block="waxed_copper_chest")
    c.chest("logs", 14, 10, [("oak_log", 1)])
    x, y, z = c.abs(8, 14)
    c.run(f"summon minecraft:iron_golem {x + 0.5} {y} {z + 0.5} {{Tags:[\"itest_other\"]}}")
    golem(c, 4, 10)
    def check():
        lines = [l for l in c.log_lines() if "[CopperGolemClerk]" in l and c.in_cell(l)]
        return [expect(c, "logs", "birch_log", 8),
                (not any("IRON_GOLEM" in l for l in lines), "no plugin log line about the iron golem")]
    return check


@scenario("mode-marker-after-unload", "modes", 90, "The chunks unload and load again: the golem has exactly one marker again (markers aren't saved, so none pile up).")
def _(c):
    golem(c, 4, 10)
    state = {"step": 0}
    def check():
        if state["step"] == 0:
            if markers(c) != 1:
                return [(False, "waiting for the marker")]
            c.forceload(False)
            state["step"], state["t"] = 1, c.elapsed()
        if state["step"] == 1:
            if c.elapsed() - state["t"] < 15:
                return [(False, "letting the chunks unload")]
            c.forceload(True)
            state["step"], state["t"] = 2, c.elapsed()
        return [(c.elapsed() - state["t"] > 8, "8 seconds after loading"), (markers(c) == 1, f"one marker (got {markers(c)})"),
                (c.golems() == 1, f"the golem is back (got {c.golems()})")]
    return check


@scenario("mode-marker-statue", "modes", 60, "A golem turning into a statue loses its marker.")
def _(c):
    golem(c, 4, 10)
    sel = c.selector("copper_golem")[:-1] + ",limit=1]"
    state = {"step": 0}
    def check():
        if state["step"] == 0:
            if markers(c) != 1:
                return [(False, "waiting for the marker")]
            c.run(f'data merge entity {sel} {{weather_state:"oxidized",next_weather_age:1L}}')
            state["step"] = 1
        return [(c.golems() == 0, "turned into a statue"), (markers(c) == 0, f"no marker left (got {markers(c)})")]
    return check


# ---- switching mid-task, and vanilla golems keeping vanilla's limits

from arena import Trigger, conserved  # noqa: E402
from suite_options import set_options  # noqa: E402


def make_vanilla(c):
    sel = c.selector("copper_golem")[:-1] + ",limit=1]"
    c.r.cmd(f"data merge entity {sel} {{{VANILLA}}}")


@scenario("vanilla-switch-mid-fetch", "modes", 90, "A golem fetching from [Overflow] is switched to vanilla on the way: it stops, and the granite stays in the overflow chest.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    c.chest("overflow", 18, 4, [("granite", 16)])
    c.sign(18, 4, "[Overflow]")
    c.chest("stone", 10, 16, [("andesite", 1)])
    x, y, z = c.abs(12, 0)
    _, _, z2 = c.abs(12, 17)
    c.run(f"fill {x} {y} {z} {x} {y + 2} {z2} minecraft:glass")  # a long walk, so the switch comes first
    golem(c, 4, 10)
    t = Trigger()
    def check():
        t.fire_when(lambda: any("fetching GRANITE" in l and c.in_cell(l) for l in c.log_lines()), lambda: make_vanilla(c))
        return [(t.at is not None and t.since() > 30, "switched mid-fetch, 30 seconds ago"),
                expect(c, "overflow", "granite", 16), (c.holding() == 0, f"golem holding {c.holding()} (want 0)")]
    return check


@scenario("vanilla-keeps-homeless-item", "modes", 150, "A vanilla-mode golem holding dirt with nowhere to put it keeps holding it, as in vanilla: the plugin doesn't return it.", alone=True)
def _(c):
    set_options(c, matching_chest_timeout_seconds="10")
    c.chest("copper", 2, 10, [("dirt", 8)], block="waxed_copper_chest")
    c.chest("logs", 14, 10, [("oak_log", 1)])
    golem(c, 4, 10, vanilla=True)
    return lambda: [(c.elapsed() > 100, "waited 100 seconds (a plugin golem returns it after about 40)"),
                    (c.holding("dirt") == 8, f"golem holding {c.holding('dirt')} dirt (want 8)"), conserved(c, "dirt", 8)]


@scenario("vanilla-switch-mid-sidestep", "modes", 420, "A golem stepping aside for another is switched to vanilla: it's woken at once, none left frozen.",
          alone=True)  # acts during a 1-2 second sidestep: under parallel load the harness polls too slowly
def _(c):
    from suite_traffic import MARKED, corridor, frozen, wait_for_sidestep
    corridor(c)
    t = Trigger()
    def switch():
        sel = c.selector("copper_golem")[:-1] + f",nbt={MARKED},limit=1]"
        c.r.cmd(f"data merge entity {sel} {{{VANILLA}}}")
    def check():
        t.fire_when(lambda: wait_for_sidestep(c), switch)
        if t.at is None:
            return [(False, "waiting for a golem to start giving way")]
        return [(t.since() > 3, "3 seconds after the switch"), (frozen(c) == 0, f"{frozen(c)} golems still paused (want 0)")]
    return check


@scenario("mode-marker-nether", "modes", 30, "In the Nether, a golem gets its marker too.", dimension="the_nether")
def _(c):
    golem(c, 4, 10)
    return lambda: [(markers(c) == 1, f"one marker (got {markers(c)})")]


@scenario("markers-many-golems", "modes", 120, "40 golems with markers (each moved every tick): ticks stay under 40 ms and within 10 ms of the baseline.", alone=True)
def _(c):
    from suite_perf import mspt
    baseline = mspt(c)
    for dx in range(1, 21, 3):
        for dz in range(1, 21, 4):
            golem(c, dx, dz)
    def check():
        now = mspt(c)
        return [(c.elapsed() > 60, "a minute with 40 golems"), (markers(c) >= 35, f"{markers(c)} markers"),
                (now is not None and now < 40 and (baseline is None or now - baseline < 10),
                 f"{now} ms per tick (baseline {baseline})")]
    return check
