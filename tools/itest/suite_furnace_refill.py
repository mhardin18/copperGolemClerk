"""furnace-refill: furnace-refill-below and furnace-wait-seconds, and furnace-feeding: false
turning every furnace rule off. Furnaces here have no fuel unless said, so nothing cooks
and their contents stay put while a scenario watches.
"""
from arena import Trigger, expect, scenario
from suite_furnace import furnace, hopper, supply
from suite_furnace_edge import no_errors, logged
from suite_options import set_options


def room(c, input=None, golem_at=(4, 4)):
    supply(c, items=[("oak_log", 16)])
    furnace(c, "furnace", 8, 10, input=input, label="[logs]")
    c.chest("logs", 4, 16, [("birch_log", 1)])
    c.sign(4, 16, "[logs]")
    c.golem(*golem_at)
    return c.abs(8, 10)


def oak_conserved(c, want=16):
    got = c.total("oak_log")
    return got == want, f"{got} oak logs exist (want {want}: none lost or duplicated)"


def fill_when_carrying(c, xyz, t, amount=64):
    x, y, z = xyz
    t.fire_when(lambda: c.holding("oak_log") > 0 and c.count("furnace", "oak_log") in (0, 8),
                lambda: c.r.cmd(f"item replace block {x} {y} {z} container.0 with minecraft:oak_log {amount}"))


@scenario("refill-waits-above-threshold", "furnace-refill", 60, "A [logs] furnace with 9 logs waiting (furnace-refill-below 8): golems leave the supply alone.")
def _(c):
    room(c, input=("oak_log", 9))
    return lambda: [(c.elapsed() > 40, "waited 40s"), expect(c, "supply", "oak_log", 16), no_errors(c)]


@scenario("refill-at-threshold", "furnace-refill", 90, "A [logs] furnace with 8 logs waiting: golems top it up with a full load (16).")
def _(c):
    room(c, input=("oak_log", 8))
    return lambda: [expect(c, "furnace", "oak_log", 24), expect(c, "supply", "oak_log", 0), expect(c, "logs", "oak_log", 0), no_errors(c)]


@scenario("option-refill-below-0", "furnace-refill", 120, "furnace-refill-below: 0: a furnace with 1 log waiting isn't topped up; once it's empty, it is.", alone=True)
def _(c):
    set_options(c, furnace_refill_below="0")
    x, y, z = room(c, input=("oak_log", 1))
    t = Trigger()

    def check():
        t.fire_when(lambda: c.elapsed() > 30, lambda: c.r.cmd(f"item replace block {x} {y} {z} container.0 with minecraft:air"))
        untouched = t.at is not None or c.count("supply", "oak_log") == 16
        return [(t.at is not None, "waited 30s with 1 log in it, then emptied it"), (untouched, "not topped up while 1 log was left"),
                expect(c, "furnace", "oak_log", 16), no_errors(c)]
    return check


@scenario("option-refill-below-64", "furnace-refill", 90, "furnace-refill-below: 64: a furnace with 60 logs waiting is topped up with the 4 that fit; the rest stay in the supply.", alone=True)
def _(c):
    set_options(c, furnace_refill_below="64")
    room(c, input=("oak_log", 60))
    return lambda: [expect(c, "furnace", "oak_log", 64), expect(c, "supply", "oak_log", 12), expect(c, "logs", "oak_log", 0),
                    (c.holding() == 0, f"golem holding {c.holding()} (want 0)"), no_errors(c)]


@scenario("wait-then-puts-in", "furnace-refill", 150, "The furnace fills up while a golem carries 16 logs to it; 10 places free up while it waits: it puts 10 in and takes the other 6 back to the supply.")
def _(c):
    xyz = room(c)
    x, y, z = xyz
    filled, freed = Trigger(), Trigger()

    def check():
        fill_when_carrying(c, xyz, filled)
        freed.fire_when(lambda: logged(c, "waits up to"), lambda: c.r.cmd(f"item replace block {x} {y} {z} container.0 with minecraft:oak_log 54"))
        return [(freed.at is not None, "the golem waited at the full furnace, then 10 places freed up"),
                expect(c, "furnace", "oak_log", 64), expect(c, "supply", "oak_log", 6), expect(c, "logs", "oak_log", 0),
                (c.holding() == 0, f"golem holding {c.holding()} (want 0)"), no_errors(c)]
    return check


@scenario("option-wait-seconds-0", "furnace-refill", 120, "furnace-wait-seconds: 0: when the furnace has filled up, the golem takes the logs straight back to the supply, without waiting.", alone=True)
def _(c):
    set_options(c, furnace_wait_seconds="0")
    xyz = room(c)
    t = Trigger()
    back = {}

    def check():
        fill_when_carrying(c, xyz, t)
        if t.at is not None and "at" not in back and c.count("supply", "oak_log") == 16:
            back["at"] = t.since()
        return [(t.at is not None, "filled the furnace while the golem carried logs"),
                ("at" in back and back["at"] < 15, f"back in the supply {back.get('at', '?')}s after (want under 15s)"),
                (not logged(c, "waits up to"), "the golem didn't wait"), no_errors(c)]
    return check


@scenario("option-wait-seconds-5", "furnace-refill", 120, "furnace-wait-seconds: 5 and the furnace never frees up: the golem waits about 5 seconds, then takes the logs back to the supply.", alone=True)
def _(c):
    set_options(c, furnace_wait_seconds="5")
    xyz = room(c)
    t = Trigger()

    def check():
        fill_when_carrying(c, xyz, t)
        return [(logged(c, "waits up to 5s"), "waited up to 5 seconds"), expect(c, "supply", "oak_log", 16),
                expect(c, "furnace", "oak_log", 64), (c.holding() == 0, f"golem holding {c.holding()} (want 0)"), no_errors(c)]
    return check


@scenario("wait-furnace-broken", "furnace-refill", 120, "The furnace is broken while a golem waits beside it: it stops waiting and puts the logs back in the supply.")
def _(c):
    xyz = room(c)
    x, y, z = xyz
    filled, broke = Trigger(), Trigger()

    def check():
        fill_when_carrying(c, xyz, filled)
        broke.fire_when(lambda: logged(c, "waits up to"), lambda: c.r.cmd(f"setblock {x} {y} {z} minecraft:air"))
        return [(broke.at is not None, "broke the furnace while the golem waited"), expect(c, "supply", "oak_log", 16),
                (c.holding() == 0, f"golem holding {c.holding()} (want 0)"), no_errors(c)]
    return check


@scenario("wait-supply-broken", "furnace-refill", 180, "The supply chest is broken while a golem waits at the full furnace: when the wait ends it sorts the logs into the [logs] chest; none lost.")
def _(c):
    xyz = room(c)
    sx, sy, sz = c.chests["supply"][0]
    filled, broke = Trigger(), Trigger()

    def check():
        fill_when_carrying(c, xyz, filled)
        broke.fire_when(lambda: logged(c, "waits up to"), lambda: c.r.cmd(f"setblock {sx} {sy} {sz} minecraft:air"))
        return [(broke.at is not None, "broke the supply while the golem waited"), expect(c, "logs", "oak_log", 16),
                (c.holding() == 0, f"golem holding {c.holding()} (want 0)"), no_errors(c)]
    return check


@scenario("wait-golem-killed", "furnace-refill", 120, "A golem waiting at a full furnace is killed: its 16 logs drop; none vanish.")
def _(c):
    xyz = room(c)
    filled, killed = Trigger(), Trigger()

    def check():
        fill_when_carrying(c, xyz, filled)
        killed.fire_when(lambda: logged(c, "waits up to"), lambda: c.r.cmd(f"kill {c.selector('copper_golem')}"))
        return [(killed.at is not None and killed.since() > 3, "killed the waiting golem"),
                (c.dropped("oak_log") == 16, f"{c.dropped('oak_log')} oak logs on the ground (want 16)"), no_errors(c)]
    return check


@scenario("wait-feeding-turned-off", "furnace-refill", 180, "furnace-feeding is turned off while a golem waits at a full furnace: it stops and sorts the logs into the [logs] chest.", alone=True)
def _(c):
    xyz = room(c)
    filled, off = Trigger(), Trigger()

    def check():
        fill_when_carrying(c, xyz, filled)
        off.fire_when(lambda: logged(c, "waits up to"), lambda: set_options(c, furnace_feeding="false"))
        return [(off.at is not None, "turned furnace feeding off mid-wait"), expect(c, "logs", "oak_log", 16),
                (c.holding() == 0, f"golem holding {c.holding()} (want 0)"), oak_conserved(c, 16 + 64), no_errors(c)]
    return check


@scenario("refill-settings-out-of-range", "furnace-refill", 30, "furnace-refill-below: -5 and furnace-wait-seconds: 99999 are warned about and clamped (0 and 300).", alone=True)
def _(c):
    set_options(c, furnace_refill_below="-5", furnace_wait_seconds="99999")
    def check():
        log = "\n".join(c.log_lines())
        return [("furnace-refill-below must be 0 to 64 (it's -5); using 0." in log, "warned about furnace-refill-below -5"),
                ("furnace-wait-seconds must be 0 to 300 (it's 99999); using 300." in log, "warned about furnace-wait-seconds 99999")]
    return check


@scenario("feeding-off-turns-everything-off", "furnace-refill", 180, "furnace-feeding: false: an empty [Furnace Supply] chest and a chest on a smelter's hopper are ordinary destinations again (the dirt goes into them), and the labelled furnace is left alone.", alone=True)
def _(c):
    set_options(c, furnace_feeding="false")
    c.chest("copper", 2, 10, [("dirt", 16), ("dirt", 16)], block="waxed_copper_chest")
    c.chest("supply", 6, 4)
    c.sign(6, 4, "[Furnace Supply]")
    furnace(c, "furnace", 12, 10, label="[logs]")
    hopper(c, "hopper", 12, 10, "down")
    c.chest("feeder", 12, 10, dy=2)
    c.golem(4, 10)
    return lambda: [(c.count("supply", "dirt") + c.count("feeder", "dirt") + c.count("hopper", "dirt") + c.count("furnace", "dirt") == 32,
                     f"dirt: supply {c.count('supply', 'dirt')}, feeder {c.count('feeder', 'dirt')}, hopper {c.count('hopper', 'dirt')} (want all 32 there)"),
                    (c.count("supply", "dirt") > 0, "some dirt went into the [Furnace Supply] chest"), no_errors(c)]
