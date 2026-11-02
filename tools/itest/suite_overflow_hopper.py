"""overflow-hopper: [Overflow] chests a hopper fills are fetched from only once
overflow-fetch-min of an item are there, or it has waited overflow-fetch-wait-seconds.
Overflow chests without a hopper feeding them are emptied right away.

Hoppers here are empty, so they never move anything themselves.
"""
from arena import Trigger, expect, scenario
from suite_furnace_edge import no_errors
from suite_options import set_options


def room(c, ingots, hopper=None):
    """An [Overflow] chest with some copper ingots, an ingot chest and an idle golem.
    hopper: None, "above" (facing down into the chest), "side-in" (beside it, facing
    into it) or "side-out" (beside it, facing away)."""
    c.chest("copper", 2, 10, block="waxed_copper_chest")  # empty: nothing else to do
    c.chest("overflow", 8, 10, [("copper_ingot", ingots)] if ingots else [])
    c.sign(8, 10, "[Overflow]")
    if hopper == "above":
        c.chest("hopper", 8, 10, block="hopper", dy=1, facing="down")
    elif hopper == "side-in":
        c.chest("hopper", 9, 10, block="hopper", facing="west")
    elif hopper == "side-out":
        c.chest("hopper", 9, 10, block="hopper", facing="east")
    c.chest("ingots", 14, 10, [("copper_ingot", 1)])
    c.sign(14, 10, "[copper ingot]")
    c.golem(4, 10)


def add_ingots(c, n):
    x, y, z = c.chests["overflow"][0]
    c.r.cmd(f"item replace block {x} {y} {z} container.1 with minecraft:copper_ingot {n}")


def waits_then_fetches(c, start, more):
    """3 ingots sit for 40s (fewer than 8); then `more` are added and they're fetched."""
    t = Trigger()
    early = []

    def check():
        if t.at is None and c.count("ingots", "copper_ingot") > 1:
            early.append(round(c.elapsed()))
        t.fire_when(lambda: c.elapsed() > 40, lambda: add_ingots(c, more))
        return [(not early, f"fetched after {early[0]}s with only {start} there" if early else f"{start} ingots left alone for 40s"),
                expect(c, "ingots", "copper_ingot", 1 + start + more), expect(c, "overflow", "copper_ingot", 0), no_errors(c)]
    return check


@scenario("overflow-hopper-above-waits-for-8", "overflow-hopper", 150, "An [Overflow] chest with a hopper on top and 3 copper ingots: left alone for 40s; once 8 are there, fetched.")
def _(c):
    room(c, 3, hopper="above")
    return waits_then_fetches(c, 3, 5)


@scenario("overflow-hopper-side-waits-for-8", "overflow-hopper", 150, "The same with a hopper beside the chest facing into it.")
def _(c):
    room(c, 3, hopper="side-in")
    return waits_then_fetches(c, 3, 5)


@scenario("overflow-hopper-wait-restarts", "overflow-hopper", 150, "A hopper-fed [Overflow] chest: 8 copper ingots are fetched; one more dropped in right after waits (it isn't fetched within 40s).")
def _(c):
    room(c, 8, hopper="above")
    t = Trigger()
    early = []

    def check():
        t.fire_when(lambda: c.count("ingots", "copper_ingot") >= 9, lambda: add_ingots(c, 1))
        if t.at is not None and t.since() < 40 and c.count("overflow", "copper_ingot") == 0:
            early.append(round(t.since()))
        return [(t.at is not None, "the first 8 were fetched, then 1 more dropped in"),
                (not early, f"the single ingot was fetched {early[0]}s later" if early else "the single ingot waited"),
                (t.at is not None and t.since() > 40, "watched it for 40s"), no_errors(c)]
    return check


@scenario("overflow-hopper-facing-away-fetched", "overflow-hopper", 90, "A hopper beside the [Overflow] chest facing away (it doesn't fill it): 3 copper ingots are fetched right away.")
def _(c):
    room(c, 3, hopper="side-out")
    return lambda: [expect(c, "ingots", "copper_ingot", 4), (c.elapsed() < 60, f"fetched within 60s ({c.elapsed():.0f}s)"), no_errors(c)]


@scenario("overflow-no-hopper-fetched", "overflow-hopper", 90, "An [Overflow] chest no hopper feeds: a single copper ingot is fetched right away.")
def _(c):
    room(c, 1)
    return lambda: [expect(c, "ingots", "copper_ingot", 2), (c.elapsed() < 60, f"fetched within 60s ({c.elapsed():.0f}s)"), no_errors(c)]


@scenario("overflow-hopper-wait-expires", "overflow-hopper", 120, "overflow-fetch-wait-seconds: 20: 3 copper ingots in a hopper-fed [Overflow] chest are fetched after about 20 seconds.", alone=True)
def _(c):
    set_options(c, overflow_fetch_wait_seconds="20")
    room(c, 3, hopper="above")
    done = []

    def check():
        if not done and c.count("ingots", "copper_ingot") == 4:
            done.append(c.elapsed())
        return [(bool(done) and done[0] >= 15, f"fetched after {done[0]:.0f}s (want about 20)" if done else "not fetched yet"),
                expect(c, "overflow", "copper_ingot", 0), no_errors(c)]
    return check


@scenario("option-overflow-fetch-min-1", "overflow-hopper", 90, "overflow-fetch-min: 1: a single copper ingot in a hopper-fed [Overflow] chest is fetched right away.", alone=True)
def _(c):
    set_options(c, overflow_fetch_min="1")
    room(c, 1, hopper="above")
    return lambda: [expect(c, "ingots", "copper_ingot", 2), (c.elapsed() < 60, f"fetched within 60s ({c.elapsed():.0f}s)"), no_errors(c)]


@scenario("overflow-hopper-tools-full-carry", "overflow-hopper", 90, "A hopper-fed [Overflow] chest with one iron pickaxe (stacks to 1): fetched right away, since one is a full carry.")
def _(c):
    room(c, 0, hopper="above")
    x, y, z = c.chests["overflow"][0]
    c.run(f"item replace block {x} {y} {z} container.0 with minecraft:iron_pickaxe 1")
    c.chest("tools", 14, 16, [("iron_pickaxe", 1)])
    c.sign(14, 16, "[iron pickaxe]")
    return lambda: [expect(c, "tools", "iron_pickaxe", 2), no_errors(c)]


@scenario("overflow-fetch-settings-out-of-range", "overflow-hopper", 30, "overflow-fetch-min 0 and overflow-fetch-wait-seconds 9999 are warned about and clamped (1, 3600).", alone=True)
def _(c):
    set_options(c, overflow_fetch_min="0", overflow_fetch_wait_seconds="9999")

    def check():
        log = "\n".join(c.log_lines())
        return [("overflow-fetch-min must be 1 to 64 (it's 0); using 1." in log, "warned about overflow-fetch-min 0"),
                ("overflow-fetch-wait-seconds must be 0 to 3600 (it's 9999); using 3600." in log, "warned about overflow-fetch-wait-seconds 9999")]
    return check
