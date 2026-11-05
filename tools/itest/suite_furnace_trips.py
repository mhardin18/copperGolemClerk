"""furnace-trips: fewer, fuller trips (furnace-take-out-at, furnace-fuel-ahead,
furnace-split-min, furnace-refill-below), leftover fuel back to [Fuel], and two golems
never on the same job at one furnace. Plus a manual copy of a real smelter that measures
trips and idle furnaces over six minutes.
"""
import re
import threading
import time

from arena import Trigger, expect, scenario
import arena
from rcon import from_properties
from suite_furnace import furnace, hopper, slot, supply
from suite_furnace_edge import no_errors, logged
from suite_options import set_options


# ---- furnace-take-out-at

def cooking(c, result, input=("raw_iron", 16), fuel=("coal", 4)):
    """A labelled furnace cooking raw iron (10s an item) with `result` ingots already made."""
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    furnace(c, "furnace", 8, 10, input=input, fuel=fuel, result=("iron_ingot", result) if result else None, label="[raw iron]")
    c.chest("ingots", 14, 10, [("iron_ingot", 1)])
    c.golem(4, 10)


@scenario("take-out-waits-for-8", "furnace-trips", 150, "A furnace cooking raw iron with 3 ingots made: golems leave them until 8 are ready (furnace-take-out-at), then take them all at once.")
def _(c):
    cooking(c, 3)
    early = []

    def check():
        if c.elapsed() < 35 and c.count("ingots", "iron_ingot") > 1:
            early.append(c.elapsed())
        return [(not early, f"ingots taken out after {early[0]:.0f}s, with fewer than 8 made" if early else "none taken out before 8 were ready"),
                (c.count("ingots", "iron_ingot") >= 9, f"ingot chest has {c.count('ingots', 'iron_ingot')} (want 9 or more: one trip of 8+)"),
                no_errors(c)]
    return check


@scenario("take-out-when-done", "furnace-trips", 90, "A furnace with nothing left to cook and 3 ingots made: golems take them out (fewer than 8, but the furnace has stopped).")
def _(c):
    cooking(c, 3, input=None, fuel=None)
    return lambda: [expect(c, "ingots", "iron_ingot", 4), expect(c, "furnace", "iron_ingot", 0), no_errors(c)]


@scenario("take-out-when-out-of-fuel", "furnace-trips", 90, "A furnace with raw iron waiting, no fuel, no [Fuel] chest and 3 ingots made: golems take them out (it can't make more).")
def _(c):
    cooking(c, 3, fuel=None)
    return lambda: [expect(c, "ingots", "iron_ingot", 4), no_errors(c)]


@scenario("option-take-out-at-1", "furnace-trips", 90, "furnace-take-out-at: 1: a furnace still cooking with 1 ingot made gets it taken out.", alone=True)
def _(c):
    set_options(c, furnace_take_out_at="1")
    cooking(c, 1)
    return lambda: [(c.count("ingots", "iron_ingot") >= 2, f"ingot chest has {c.count('ingots', 'iron_ingot')} (want 2 or more)"), no_errors(c)]


@scenario("option-take-out-at-64", "furnace-trips", 90, "furnace-take-out-at: 64: a furnace still cooking with 20 ingots made keeps them for 40s.", alone=True)
def _(c):
    set_options(c, furnace_take_out_at="64")
    cooking(c, 20)
    return lambda: [(c.elapsed() > 40, "waited 40s"), expect(c, "ingots", "iron_ingot", 1), no_errors(c)]


@scenario("take-out-full-output-first", "furnace-trips", 120, "A furnace whose output holds 64 ingots (stopped) and a furnace running low with a supply nearby: the full one is emptied before the other gets its load.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    furnace(c, "full", 8, 6, input=("raw_iron", 4), fuel=("coal", 4), result=("iron_ingot", 64), label="[raw iron]")
    furnace(c, "low", 8, 14, label="[logs]")
    supply(c, items=[("oak_log", 16)])
    c.chest("ingots", 14, 4, [("iron_ingot", 1)])
    c.golem(4, 10)
    order = []

    def check():
        if not order and c.count("full", "iron_ingot") < 64:
            order.append("emptied")
        if c.count("low", "oak_log") > 0 and "fed" not in order:
            order.append("fed")
        return [(order[:1] == ["emptied"], f"first job: {order[:1]} (want the full furnace emptied first)"),
                (c.count("ingots", "iron_ingot") > 1, f"ingot chest has {c.count('ingots', 'iron_ingot')}"), no_errors(c)]
    return check


# ---- furnace-fuel-ahead

def fuel_room(c, raw=8, golems=1, fuel_at=(14, 10)):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    furnace(c, "furnace", 8, 10, input=("raw_iron", raw), label="[raw iron]")
    c.chest("fuelchest", *fuel_at, [("charcoal", 32)])
    c.sign(*fuel_at, "[Fuel]")
    c.chest("overflow", 14, 16)
    c.sign(14, 16, "[Overflow]")
    for i in range(golems):
        c.golem(4, 8 + 4 * i)


def settled_fuel(c, want_left):
    t = Trigger()

    def check():
        t.fire_when(lambda: c.count("fuelchest", "charcoal") < 32, lambda: None)
        settled = t.at is not None and t.since() > 15 and c.holding("charcoal") == 0
        return [(settled, "fuel delivered and the golems' hands empty for 15s"),
                expect(c, "fuelchest", "charcoal", want_left), expect(c, "overflow", "charcoal", 0), no_errors(c)]
    return check


@scenario("fuel-ahead-default", "furnace-trips", 150, "8 raw iron waiting: golems bring 3 charcoal (8 waiting + 16 ahead = 24 items), in one trip.")
def _(c):
    fuel_room(c)
    return settled_fuel(c, 29)


@scenario("option-fuel-ahead-0", "furnace-trips", 150, "furnace-fuel-ahead: 0: 8 raw iron waiting get exactly 1 charcoal.", alone=True)
def _(c):
    set_options(c, furnace_fuel_ahead="0")
    fuel_room(c)
    return settled_fuel(c, 31)


@scenario("fuel-one-golem-per-furnace", "furnace-trips", 150, "Two idle golems, one furnace needing fuel: only one fetches it (3 charcoal leave the [Fuel] chest, none end up in [Overflow]).")
def _(c):
    fuel_room(c, golems=2)
    most = [0]

    def check():
        holders = len(re.findall(r"has the following", c.r.cmd(
            f"execute as {c.selector('copper_golem')} if items entity @s weapon.mainhand minecraft:charcoal run data get entity @s UUID")))
        most[0] = max(most[0], holders)
        return settled_fuel_check() + [(most[0] <= 1, f"at most {most[0]} golem(s) carried charcoal at once (want 1)")]
    settled_fuel_check = settled_fuel(c, 29)
    return check


@scenario("fuel-leftover-back-to-fuel", "furnace-trips", 150, "The furnace gets fuelled by someone else while a golem carries charcoal to it: the golem puts its charcoal back in the [Fuel] chest, not in [Overflow].")
def _(c):
    fuel_room(c, fuel_at=(19, 19))  # a long walk back to the furnace, so the check catches it carrying
    x, y, z = c.abs(8, 10)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.count("fuelchest", "charcoal") < 32,
                    lambda: c.r.cmd(f"item replace block {x} {y} {z} container.1 with minecraft:coal 16"))
        back = t.at is not None and t.since() > 5 and c.holding("charcoal") == 0
        return [(t.at is not None, "fuelled the furnace while the golem carried charcoal"), (back, "golem's hands empty again"),
                expect(c, "fuelchest", "charcoal", 32), expect(c, "overflow", "charcoal", 0),
                (slot_fuel(c).startswith("coal "), f"fuel slot: '{slot_fuel(c)}' (want only the coal: no charcoal added)"), no_errors(c)]
    return check


def slot_fuel(c):
    return slot(c, "furnace", 1)


# ---- furnace-split-min and furnace-refill-below

def three_furnaces(c, waiting=(0, 0, 0)):
    """Three unfuelled [logs] furnaces (nothing cooks), nearest first from the golem, and 16 logs in the supply."""
    supply(c, dx=16, dz=10, items=[("oak_log", 16)])
    for name, dz, n in zip(("f1", "f2", "f3"), (8, 12, 16), waiting):
        furnace(c, name, 12, dz, input=("oak_log", n) if n else None, label="[logs]")
    c.chest("logs", 4, 18, [("birch_log", 1)])
    c.sign(4, 18, "[logs]")
    c.golem(14, 6)


def logs_in(c):
    return [c.count(n, "oak_log") for n in ("f1", "f2", "f3")]


@scenario("split-min-two-furnaces", "furnace-trips", 120, "16 logs and three empty [logs] furnaces: the load goes to two of them, 8 each (furnace-split-min 8).")
def _(c):
    three_furnaces(c)
    return lambda: [(sorted(logs_in(c)) == [0, 8, 8], f"logs in the furnaces: {logs_in(c)} (want two with 8, one empty)"),
                    expect(c, "supply", "oak_log", 0), no_errors(c)]


@scenario("split-emptiest-first", "furnace-trips", 120, "16 logs; the nearest [logs] furnace has 12 waiting, the other two are empty: the empty ones get the load.")
def _(c):
    three_furnaces(c, waiting=(12, 0, 0))
    return lambda: [(logs_in(c) == [12, 8, 8], f"logs in the furnaces: {logs_in(c)} (want [12, 8, 8])"),
                    expect(c, "supply", "oak_log", 0), no_errors(c)]


@scenario("option-split-min-1", "furnace-trips", 120, "furnace-split-min: 1: 16 logs and three empty furnaces: still only 2 get the load (8 + 8; never more than 2 furnaces per load).", alone=True)
def _(c):
    set_options(c, furnace_split_min="1")
    three_furnaces(c)
    return lambda: [(sorted(logs_in(c)) == [0, 8, 8], f"logs in the furnaces: {logs_in(c)} (want two with 8, one empty)"),
                    no_errors(c)]


@scenario("option-split-min-16", "furnace-trips", 120, "furnace-split-min: 16: 16 logs and three empty furnaces: the whole load goes into one.", alone=True)
def _(c):
    set_options(c, furnace_split_min="16")
    three_furnaces(c)
    return lambda: [(sorted(logs_in(c)) == [0, 0, 16], f"logs in the furnaces: {logs_in(c)} (want one with 16)"), no_errors(c)]


@scenario("supply-one-golem-per-load", "furnace-trips", 150, "Three idle golems, one empty [logs] furnace, 32 logs in the supply: two loads of 16 go in, and only two trips to the supply are started (no golem walks there for nothing).")
def _(c):
    supply(c, items=[("oak_log", 16), ("oak_log", 16)])
    furnace(c, "furnace", 8, 10, label="[logs]")
    for dz in (4, 10, 16):
        c.golem(3, dz)
    t = Trigger()

    def check():
        trips = [l for l in c.cell_lines() if "(SUPPLY_TAKE)" in l]
        t.fire_when(lambda: c.count("furnace", "oak_log") == 32, lambda: None)
        return [(t.at is not None and t.since() > 10, "furnace got 32 logs, then 10s more"),
                (len(trips) == 2, f"{len(trips)} trips to the supply started (want 2)"),
                expect(c, "supply", "oak_log", 0), no_errors(c)]
    return check


@scenario("refill-at-16-default", "furnace-trips", 90, "A [logs] furnace with 16 logs waiting (furnace-refill-below 16): golems top it up.")
def _(c):
    supply(c, items=[("oak_log", 16)])
    furnace(c, "furnace", 8, 10, input=("oak_log", 16), label="[logs]")
    c.golem(4, 4)
    return lambda: [expect(c, "furnace", "oak_log", 32), expect(c, "supply", "oak_log", 0), no_errors(c)]


@scenario("trip-settings-out-of-range", "furnace-trips", 30, "furnace-take-out-at 0, furnace-fuel-ahead 99 and furnace-split-min 0 are warned about and clamped (1, 64, 1).", alone=True)
def _(c):
    set_options(c, furnace_take_out_at="0", furnace_fuel_ahead="99", furnace_split_min="0")

    def check():
        log = "\n".join(c.log_lines())
        return [("furnace-take-out-at must be 1 to 64 (it's 0); using 1." in log, "warned about furnace-take-out-at 0"),
                ("furnace-fuel-ahead must be 0 to 64 (it's 99); using 64." in log, "warned about furnace-fuel-ahead 99"),
                ("furnace-split-min must be 1 to 64 (it's 0); using 1." in log, "warned about furnace-split-min 0")]
    return check


# ---- a copy of a real smelter (survival, 2026-11-01), measured

SMELTER_SECONDS = 360


class Sampler(threading.Thread):
    """Once a second: what each golem holds and what each furnace holds. Its own RCON connection."""

    def __init__(self, c, furnaces):
        super().__init__(daemon=True)
        self.c, self.furnaces = c, furnaces
        self.r = from_properties(arena.SERVER + "/server.properties")
        self.hands, self.trips, self.loads = {}, {}, {}
        self.idle = {n: 0 for n in furnaces}
        self.unlit = {n: 0 for n in furnaces}
        self.samples = 0
        self.stop = False

    def run(self):
        sel = self.c.selector("copper_golem")
        while not self.stop:
            try:
                uuids = re.findall(r"\[I; ([-\d, ]+)\]", self.r.cmd(f"execute as {sel} run data get entity @s UUID"))
                for u in uuids:
                    one = f"@e[type=copper_golem,nbt={{UUID:[I;{u.replace(' ', '')}]}},limit=1]"
                    out = self.r.cmd(f"data get entity {one} equipment.mainhand")
                    m = re.search(r'id: "minecraft:(\w+)", count: (\d+)', out)
                    now = (m.group(1), int(m.group(2))) if m else None
                    if now and self.hands.get(u) is None:
                        self.trips[now[0]] = self.trips.get(now[0], 0) + 1
                        self.loads.setdefault(now[0], []).append(now[1])
                    self.hands[u] = now
                for name, (x, y, z) in self.furnaces.items():
                    if "Slot: 0b" not in self.r.cmd(f"data get block {x} {y} {z} Items"):
                        self.idle[name] += 1
                    lit = re.search(r"data: (\d+)", self.r.cmd(f"data get block {x} {y} {z} lit_time_remaining"))
                    if not lit or lit.group(1) == "0":
                        self.unlit[name] += 1
                self.samples += 1
            except Exception:
                pass
            time.sleep(1)

    def report(self):
        trips = ", ".join(f"{k} {v} (avg {sum(self.loads[k]) / len(self.loads[k]):.1f})" for k, v in sorted(self.trips.items()))
        return (f"trips: {trips or 'none'}; seconds with nothing to cook: {self.idle}; "
                f"seconds not burning: {self.unlit}; over {self.samples} samples")


def smelter(c, name, golems=2, furnaces=("furnaceW", "furnaceE", "furnace3")):
    # Row along x at dz 4, like survival's z -8001 row (x -1361..-1354 -> dx 2..9).
    c.double_chest("supply", 3, 4, [("raw_copper", 64)] * 4 + [("raw_copper", 47), ("raw_gold", 3), ("raw_iron", 11)])
    c.sign(3, 4, "[Furnace Supply]")
    hopper(c, "hopperW", 4, 4, "east", dy=0)
    furnace(c, "furnaceW", 4, 4, dy=1, label="[*]")
    c.double_chest("overflow", 6, 4)
    c.sign(6, 4, "[Overflow]")
    if "furnaceE" in furnaces:
        hopper(c, "hopperE", 7, 4, "west", dy=0)
        furnace(c, "furnaceE", 7, 4, dy=1, label="[*]")
    c.double_chest("fuel", 9, 4, [("charcoal", 64)] * 3 + [("charcoal", 35)])
    c.sign(9, 4, "[Fuel]")
    # The third furnace (-1353,70,-8011): no hopper, 10 blocks in.
    if "furnace3" in furnaces:
        furnace(c, "furnace3", 10, 14, label="[*]")
    c.chest("copper", 10, 12, block="waxed_copper_chest")
    c.chest("ingots", 14, 14, [("copper_ingot", 1)])
    c.sign(14, 14, "[ingots and gems]")
    for i in range(golems):
        c.golem(6 + 2 * i, 9)
    sampler = Sampler(c, {n: c.chests[n][0] for n in furnaces})
    sampler.start()

    def check():
        done = c.elapsed() > SMELTER_SECONDS
        if done:
            sampler.stop = True
            stray = c.count("overflow", "charcoal")
            print(f"  {name}: {sampler.report()}; charcoal in [Overflow]: {stray}; "
                  f"raw copper left in supply: {c.count('supply', 'raw_copper')}; "
                  f"copper ingots in the ingot chest: {c.count('ingots', 'copper_ingot') - 1}", flush=True)
        return [(done, f"measured for {SMELTER_SECONDS}s"), no_errors(c)]
    return check


@scenario("smelter-replica", "furnace-trips", SMELTER_SECONDS + 60, "Manual: a copy of the survival smelter (2 furnaces on hoppers into an [Overflow] double chest, a third without a hopper, [Furnace Supply] and [Fuel] double chests, 2 golems), measured for 6 minutes.", manual=True)
def _(c):
    return smelter(c, "smelter-replica")


@scenario("smelter-replica-3-golems", "furnace-trips", SMELTER_SECONDS + 60, "Manual: the same smelter with 3 golems, measured for 6 minutes.", manual=True)
def _(c):
    return smelter(c, "smelter-replica-3-golems", golems=3)


@scenario("smelter-replica-1-furnace", "furnace-trips", SMELTER_SECONDS + 60, "Manual: the same smelter with only the west furnace and 3 golems, measured for 6 minutes.", manual=True)
def _(c):
    return smelter(c, "smelter-replica-1-furnace", golems=3, furnaces=("furnaceW",))
