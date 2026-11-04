"""work-order: what an idle golem does first. Furnace upkeep (stopped furnaces, buckets, fuel,
items to cook) comes before sorting copper chests, sorting before taking out finished items,
and furnace-jobs-before-sorting makes a golem sort once after that many furnace jobs in a row.
"""
from arena import expect, scenario
from suite_furnace import furnace, slot
from suite_furnace_edge import no_errors
from suite_options import set_options


def sorting_waits(c, logs=32):
    """A copper chest of birch logs with a [logs] chest to sort them into."""
    c.chest("copper", 2, 10, [("birch_log", logs)], block="waxed_copper_chest")
    c.chest("logs", 2, 16, [("oak_log", 1)])
    c.sign(2, 16, "[logs]")


def unfuelled(c, name, dx, dz):
    """A labelled furnace with raw iron to cook and no fuel."""
    furnace(c, name, dx, dz, input=("raw_iron", 8), label="[raw iron]")


def fuel_chest(c, dx=16, dz=16, charcoal=16):
    c.chest("fuelchest", dx, dz, [("charcoal", charcoal)])
    c.sign(dx, dz, "[Fuel]")


def fuelled(c, name):
    return slot(c, name, 1) != ""


@scenario("upkeep-before-sorting", "work-order", 150, "A copper chest of logs to sort and a furnace with raw iron but no fuel: the golem fuels the furnace before it takes any logs, then sorts them.")
def _(c):
    sorting_waits(c)
    unfuelled(c, "furnace", 10, 4)
    fuel_chest(c)
    c.golem(6, 10)
    at_fuel = []

    def check():
        if not at_fuel and fuelled(c, "furnace"):
            at_fuel.append(c.count("copper", "birch_log"))
        return [(bool(at_fuel), "the furnace got fuel"),
                (bool(at_fuel) and at_fuel[0] == 32,
                 f"copper chest had {at_fuel[0] if at_fuel else '?'} logs when the furnace got fuel (want 32: none taken yet)"),
                expect(c, "logs", "birch_log", 32), no_errors(c)]
    return check


@scenario("work-order-setting-out-of-range", "work-order", 30, "furnace-jobs-before-sorting: -1 and 999 are warned about and clamped (0 and 64).", alone=True)
def _(c):
    set_options(c, furnace_jobs_before_sorting="-1")
    low = []

    def check():
        log = "\n".join(c.log_lines())
        if not low and "furnace-jobs-before-sorting must be 0 to 64 (it's -1); using 0." in log:
            low.append(True)
            set_options(c, furnace_jobs_before_sorting="999")
        return [(bool(low), "warned about furnace-jobs-before-sorting -1, using 0"),
                ("furnace-jobs-before-sorting must be 0 to 64 (it's 999); using 64." in log, "warned about 999, using 64")]
    return check


@scenario("sorts-after-n-furnace-jobs", "work-order", 240, "furnace-jobs-before-sorting: 1: three furnaces with no fuel and a copper chest of logs: after fuelling one furnace the golem sorts before fuelling the next.", alone=True)
def _(c):
    set_options(c, furnace_jobs_before_sorting="1")
    sorting_waits(c, logs=48)
    for i, dz in enumerate((4, 10, 16)):
        unfuelled(c, f"furnace{i}", 12, dz)
    fuel_chest(c, 18, 10, charcoal=32)
    c.golem(6, 10)
    first_sort = []

    def check():
        n = sum(fuelled(c, f"furnace{i}") for i in range(3))
        if not first_sort and c.count("copper", "birch_log") < 48:
            first_sort.append(n)
        return [(bool(first_sort), "the golem sorted from the copper chest"),
                (bool(first_sort) and first_sort[0] <= 1,
                 f"{first_sort[0] if first_sort else '?'} furnace(s) fuelled before the first sort (want 1 at most)"),
                (n == 3, f"{n} of 3 furnaces fuelled in the end"), no_errors(c)]
    return check


@scenario("jobs-before-sorting-1-steady-inflow", "work-order", 180, "furnace-jobs-before-sorting: 1 with 8 logs arriving every 10s: the golem alternates, fuelling all three furnaces while it keeps sorting (it used to drop every furnace job when vanilla took it to sort).", alone=True)
def _(c):
    set_options(c, furnace_jobs_before_sorting="1")
    sorting_waits(c, logs=8)
    for i, dz in enumerate((4, 10, 16)):
        unfuelled(c, f"furnace{i}", 12, dz)
    fuel_chest(c, 18, 10, charcoal=32)
    c.golem(6, 10)
    x, y, z = c.chests["copper"][0]
    state = {"next": 10, "added": 8}

    def inflow():
        while c.elapsed() >= state["next"]:
            state["next"] += 10
            free = next((n for n in range(27) if c.r.cmd(f"execute if items block {x} {y} {z} container.{n} *").find("passed") < 0), None)
            if free is not None:
                c.r.cmd(f"item replace block {x} {y} {z} container.{free} with minecraft:birch_log 8")
                state["added"] += 8

    def check():
        inflow()
        n = sum(fuelled(c, f"furnace{i}") for i in range(3))
        sorted_logs = c.count("logs", "birch_log")
        return [(n == 3, f"{n} of 3 furnaces fuelled"),
                (sorted_logs >= 32, f"{sorted_logs} logs sorted of {state['added']} added (want 32 or more)"), no_errors(c)]
    return check


@scenario("option-jobs-before-sorting-0", "work-order", 150, "furnace-jobs-before-sorting: 0: sorting comes first, as before 3.1.0: the golem takes logs from the copper chest before it fuels the furnace.", alone=True)
def _(c):
    set_options(c, furnace_jobs_before_sorting="0")
    sorting_waits(c)
    unfuelled(c, "furnace", 10, 4)
    fuel_chest(c)
    c.golem(6, 10)
    at_fuel = []

    def check():
        if not at_fuel and fuelled(c, "furnace"):
            at_fuel.append(c.count("copper", "birch_log"))
        return [(bool(at_fuel), "the furnace got fuel"),
                (bool(at_fuel) and at_fuel[0] < 32,
                 f"copper chest had {at_fuel[0] if at_fuel else '?'} logs when the furnace got fuel (want fewer than 32: sorted first)"),
                no_errors(c)]
    return check


@scenario("take-out-after-sorting", "work-order", 150, "A furnace still cooking with 8 ingots ready (worth a trip, not urgent) and a copper chest of logs: the golem sorts the logs before it takes the ingots out.")
def _(c):
    sorting_waits(c, logs=16)
    furnace(c, "furnace", 10, 4, input=("raw_iron", 16), fuel=("coal", 4), result=("iron_ingot", 8), label="[raw iron]")
    c.chest("ingots", 14, 4, [("iron_ingot", 1)])
    c.golem(6, 10)
    at_take_out = []

    def check():
        if not at_take_out and c.count("ingots", "iron_ingot") > 1:
            at_take_out.append(c.count("copper", "birch_log"))
        return [(bool(at_take_out), "the ingots were taken out"),
                (bool(at_take_out) and at_take_out[0] == 0,
                 f"copper chest had {at_take_out[0] if at_take_out else '?'} logs when the ingots came out (want 0: sorted first)"),
                no_errors(c)]
    return check


# ---- manual: what furnace-jobs-before-sorting costs each side, for picking its default

import re  # noqa: E402
import threading  # noqa: E402
import time  # noqa: E402

import arena  # noqa: E402
from rcon import from_properties  # noqa: E402
from suite_furnace_trips import Sampler  # noqa: E402

BALANCE_SECONDS = 300
BALANCE_VALUES = (0, 1, 3, 6, 12)
INFLOW = 8           # birch logs added to the copper chest ...
INFLOW_EVERY = 10    # ... every this many seconds


class Backlog(threading.Thread):
    """Once a second: logs waiting in the copper chest; every INFLOW_EVERY s, INFLOW more. Its own RCON connection."""

    def __init__(self, at):
        super().__init__(daemon=True)
        self.at = at
        self.r = from_properties(arena.SERVER + "/server.properties")
        self.waiting, self.added, self.full, self.stop = [], 0, 0, False

    def count(self, what):
        """How many of an item ('*' = anything) are in the copper chest, or in one slot of it."""
        x, y, z = self.at
        return arena._count(self.r.cmd(f"execute if items block {x} {y} {z} {what}"))

    def run(self):
        # One slot or one count at a time: a whole `data get block ... Items` is cut short by
        # the server, which hid most stacks (logs waiting read 8) and made full slots look free.
        x, y, z = self.at
        tick = 0
        while not self.stop:
            try:
                self.waiting.append(self.count("container.* minecraft:birch_log"))
                if tick % INFLOW_EVERY == 0:
                    free = next((s for s in range(27) if self.count(f"container.{s} *") == 0), None)
                    if free is not None:
                        self.r.cmd(f"item replace block {x} {y} {z} container.{free} with minecraft:birch_log {INFLOW}")
                        self.added += INFLOW
                    else:
                        self.full += 1
            except Exception:
                pass
            tick += 1
            time.sleep(1)

    def report(self):
        w = self.waiting or [0]
        # Little's law: average logs waiting / logs arriving per second = average seconds a log waits.
        rate = self.added / max(1, len(self.waiting))
        return (f"logs waiting: avg {sum(w) / len(w):.1f}, max {max(w)}; about {sum(w) / len(w) / rate if rate else 0:.0f}s "
                f"a log waits; {self.added} added" + (f" ({self.full} inflows skipped: chest full)" if self.full else ""))


def balance(c, n):
    set_options(c, furnace_jobs_before_sorting=str(n))
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    c.chest("logs", 2, 18, [("oak_log", 1)])
    c.sign(2, 18, "[logs]")
    names = ("furnace0", "furnace1", "furnace2")
    for name, dz in zip(names, (4, 10, 16)):
        furnace(c, name, 14, dz, label="[raw iron]")
    c.double_chest("supply", 20, 8, [("raw_iron", 64)] * 6)
    c.sign(20, 8, "[Furnace Supply]")
    c.chest("fuelchest", 20, 14, [("charcoal", 64)] * 2)
    c.sign(20, 14, "[Fuel]")
    c.chest("ingots", 8, 18, [("iron_ingot", 1)])
    c.golem(8, 10)
    furnaces = Sampler(c, {k: c.chests[k][0] for k in names})
    backlog = Backlog(c.chests["copper"][0])
    furnaces.start()
    backlog.start()

    def check():
        done = c.elapsed() > BALANCE_SECONDS
        if done and not furnaces.stop:
            furnaces.stop = backlog.stop = True
            print(f"  furnace-jobs-before-sorting {n}: {backlog.report()}; logs sorted: "
                  f"{c.count('logs', 'birch_log')}; ingots made: "
                  f"{sum(c.count(k, 'iron_ingot') for k in names) + c.count('ingots', 'iron_ingot') - 1}, taken out: "
                  f"{c.count('ingots', 'iron_ingot') - 1}; "
                  f"furnaces: {furnaces.report()}", flush=True)
        return [(done, f"measured for {BALANCE_SECONDS}s"), no_errors(c)]
    return check


for _n in BALANCE_VALUES:
    scenario(f"work-order-balance-{_n}", "work-order", BALANCE_SECONDS + 60,
             f"Manual: furnace-jobs-before-sorting {_n}: one golem, 3 furnaces fed from a [Furnace Supply], and a copper chest "
             f"getting {INFLOW} logs every {INFLOW_EVERY}s, for {BALANCE_SECONDS // 60} minutes; prints how long logs wait "
             f"and how long furnaces sit idle.", manual=True, alone=True)(lambda c, n=_n: balance(c, n))
