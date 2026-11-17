"""gestures: golems play vanilla's chest animation when the plugin moves the items.

Vanilla golems reach into a chest (state DROPPING_ITEM or GETTING_ITEM) while they use it.
For moves the plugin makes itself (reach deliveries, [Overflow] fetches, furnace jobs) the
plugin sets that state and holds the golem still for the lid flash, 40 ticks.
GolemTestHooks' /golemstates records every golem's state each tick; a scenario passes when
the move happened and the golem held the matching state for at least MIN_TICKS (a golem
that walked straight off would have been reset to IDLE by vanilla at once).
"""
from arena import SIZE, Skip, expect, scenario
from suite_furnace import cooked, furnace, supply
from suite_options import set_options
from suite_reach import deliver_scene

MIN_TICKS = 35  # the whole 40-tick hold, less a tick or two of sampling


class StateWatch:
    def __init__(self, c):
        self.c = c
        x1, y1, z1 = c.abs(-3, -1, -3)
        x2, y2, z2 = c.abs(SIZE + 3, SIZE + 3, 6)
        self.query = f"golemstates {x1} {y1} {z1} {x2} {y2} {z2}"
        first = c.r.cmd(self.query)
        if "Unknown" in first or "Usage" in first:
            raise Skip("needs GolemTestHooks with /golemstates on pluginDev")
        self.seen = []  # (state, ticks) of every finished stretch

    def sample(self):
        out = self.c.r.cmd(self.query).strip()
        for part in ([] if out == "none" else out.split("|")):
            words = part.split()
            if words and words[0] != "NOW":
                self.seen.append((words[0], int(words[1])))

    def longest(self, state):
        return max((t for s, t in self.seen if s == state), default=0)

    def check(self, state, what):
        got = self.longest(state)
        return got >= MIN_TICKS, f"{what}: longest {state} {got} ticks (want >= {MIN_TICKS}); seen {self.summary()}"

    def summary(self):
        counts = {}
        for s, t in self.seen:
            counts.setdefault(s, []).append(t)
        return ", ".join(f"{s} x{len(ts)} (max {max(ts)})" for s, ts in sorted(counts.items())) or "none"


def watched(c, checks):
    """Sample the states each poll; the scenario's checks, then the state checks."""
    watch = StateWatch(c)

    def result():
        watch.sample()
        return checks(watch)
    return result


@scenario("gesture-vanilla-baseline", "gestures", 180, "Control: a vanilla delivery to a row-1 chest shows as DROPPING_ITEM in /golemstates for vanilla's 60-tick interaction.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 8)], block="waxed_copper_chest")
    c.chest("logs", 14, 10)
    c.sign(14, 10, "[logs]")
    c.golem(4, 10)
    return watched(c, lambda w: [expect(c, "logs", "birch_log", 8), w.check("DROPPING_ITEM", "vanilla put")])


@scenario("gesture-reach-put", "gestures", 240, "deliver-reach: 4: the plugin puts birch logs into the [logs] chest at the top of a 4-high stack, and the golem shows the DROPPING_ITEM animation while it does.", alone=True)
def _(c):
    set_options(c, deliver_reach="4")
    deliver_scene(c)
    return watched(c, lambda w: [expect(c, "top", "birch_log", 8), w.check("DROPPING_ITEM", "plugin put")])


@scenario("gesture-overflow-fetch", "gestures", 240, "An idle golem fetches birch logs back out of [Overflow] once a [logs] chest exists: the plugin's take shows as GETTING_ITEM.")
def _(c):
    c.chest("overflow", 8, 4, [("birch_log", 8)])
    c.sign(8, 4, "[Overflow]")
    c.chest("logs", 8, 16)
    c.sign(8, 16, "[logs]")
    c.golem(8, 10)
    return watched(c, lambda w: [expect(c, "logs", "birch_log", 8), w.check("GETTING_ITEM", "plugin take")])


@scenario("gesture-furnace", "gestures", 240, "A golem takes raw iron from a [Furnace Supply] chest and feeds the labelled blast furnace: both plugin moves show their animation (GETTING_ITEM, DROPPING_ITEM).")
def _(c):
    supply(c)
    furnace(c, "blast", 8, 10, block="blast_furnace", fuel=("coal", 16), label="[raw iron]")
    c.chest("ingots", 4, 16, [("iron_ingot", 1)])
    c.golem(4, 10)
    return watched(c, lambda w: [expect(c, "supply", "raw_iron", 0),
                                 (cooked(c, "blast") + c.total("iron_ingot") - 1 == 16,
                                  f"{cooked(c, 'blast')} raw/ingots in the furnace, {c.total('iron_ingot') - 1} ingots made (want 16 raw iron used)"),
                                 w.check("GETTING_ITEM", "plugin take from the supply"),
                                 w.check("DROPPING_ITEM", "plugin put into the furnace")])
