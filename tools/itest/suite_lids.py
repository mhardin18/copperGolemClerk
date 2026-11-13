"""Chest lids left open. Each scenario keeps golems busy for a while and samples every lid
on its platform each round with GolemTestHooks' /golemlids (vanilla's opener count, the
Lidded.open() flag, and golems still holding a chest open). A lid open longer than any
golem visit explains (STUCK seconds) fails the scenario, naming the lid and the golems."""
import time

from arena import SIZE, Y, Skip, Trigger, scenario
from suite_furnace import furnace
from suite_greet import boxed, hellos
from suite_options import set_options
from suite_scenes import build_room

STUCK = 20  # a vanilla visit holds the lid about 3 s, a plugin flash 2 s; queues add a few


class LidWatch:
    def __init__(self, c, soak):
        self.c, self.soak = c, soak
        x1, y1, z1 = c.abs(-3, -3, -1)
        x2, y2, z2 = c.abs(SIZE + 3, SIZE + 3, 6)
        self.query = f"golemlids {x1} {y1} {z1} {x2} {y2} {z2}"
        if "Unknown" in c.r.cmd(self.query) or "Usage" in c.r.cmd(self.query):
            raise Skip("needs GolemTestHooks with /golemlids on pluginDev")
        self.since, self.found, self.golems, self.opened = {}, {}, [], 0

    def sample(self):
        out = self.c.r.cmd(self.query).strip()
        now = time.time()
        parts = [] if out == "none" else [p.strip() for p in out.split("|")]
        lids = {p.split()[1]: p for p in parts if p.startswith("LID ")}
        self.golems = [p for p in parts if p.startswith("GOLEM ")]
        for pos in list(self.since):
            if pos not in lids:
                del self.since[pos]
        for pos, line in lids.items():
            if pos not in self.since:
                self.since[pos] = now
                self.opened += 1
            held = now - self.since[pos]
            if held > STUCK:
                self.found[pos] = (held, line, [g for g in self.golems if g.split("-> ")[1].split()[0] == pos])
        return lids

    def results(self, extra=()):
        self.sample()
        out = [(self.c.elapsed() >= self.soak, f"kept busy {self.c.elapsed():.0f}/{self.soak}s ({self.opened} lid openings seen)")]
        out += list(extra)
        if self.found:
            for pos, (held, line, golems) in sorted(self.found.items()):
                out.append((False, f"lid stuck {held:.0f}s+: {line}; golems: {', '.join(golems) or 'none holding it'}"))
        else:
            out.append((True, f"no lid open longer than {STUCK}s"))
        return out


def soak(c, seconds):
    w = LidWatch(c, seconds)
    return lambda: w.results()


MIXED = [("birch_log", 64), ("granite", 64), ("oak_log", 64), ("andesite", 64), ("white_wool", 64),
         ("spruce_log", 64), ("diorite", 64), ("black_wool", 64), ("cobblestone", 64)]


@scenario("lids-sorting-room", "lids", 300, "A 12-chest sorting room with 3 golems: no lid stays open.")
def _(c):
    build_room(c, signs=True)
    return soak(c, 240)


@scenario("lids-crowded-chests", "lids", 300, "6 golems sort 9 stacks into a double [logs] chest, a [stone] and a [wool] chest side by side: no lid stays open.")
def _(c):
    c.chest("copper", 2, 8, MIXED[:5], block="waxed_copper_chest")
    c.chest("copper2", 2, 12, MIXED[5:], block="waxed_copper_chest")
    c.double_chest("logs", 12, 10)
    c.sign(12, 10, "[logs]")
    c.chest("stone", 13, 10)
    c.sign(13, 10, "[stone]")
    c.chest("wool", 14, 10)
    c.sign(14, 10, "[wool]")
    c.chest("overflow", 18, 10)
    c.sign(18, 10, "[Overflow]")
    for dz in (7, 9, 10, 11, 13, 14):
        c.golem(5, dz)
    return soak(c, 240)


@scenario("lids-overflow-fetch", "lids", 300, "4 golems fetch homed items back out of a full [Overflow] chest: no lid stays open.")
def _(c):
    c.chest("overflow", 10, 4, MIXED[:4] + MIXED[5:7], block="chest")
    c.sign(10, 4, "[Overflow]")
    c.chest("logs", 6, 16)
    c.sign(6, 16, "[logs]")
    c.chest("stone", 14, 16)
    c.sign(14, 16, "[stone]")
    for dx in (8, 9, 11, 12):
        c.golem(dx, 8)
    return soak(c, 240)


@scenario("lids-reach", "lids", 300, "3 golems deliver to the second chest of 2-high stacks (past vanilla's reach, opened by the plugin): no lid stays open.")
def _(c):
    c.chest("copper", 2, 10, MIXED[:4], block="waxed_copper_chest")
    for dx, low, high in ((12, "[wool]", "[logs]"), (14, "[sand]", "[stone]")):
        c.chest(f"low{dx}", dx, 10)
        c.sign(dx, 10, low)
        c.chest(f"high{dx}", dx, 10, dy=1)
        c.sign(dx, 10, high, dy=1)
    c.chest("overflow", 8, 16)
    c.sign(8, 16, "[Overflow]")
    for dz in (8, 10, 12):
        c.golem(4, dz)
    return soak(c, 240)


@scenario("lids-furnace", "lids", 300, "2 golems feed a furnace from a [Furnace Supply] chest and take out the results: no lid stays open.")
def _(c):
    c.chest("copper", 2, 10, [("raw_iron", 64), ("raw_copper", 64), ("coal", 32)], block="waxed_copper_chest")
    c.sign(2, 10, "[Furnace Supply]")
    furnace(c, "f1", 10, 8)
    furnace(c, "f2", 10, 12, block="blast_furnace")
    c.chest("ingots", 16, 10)
    c.sign(16, 10, "[Overflow]")
    c.golem(4, 9)
    c.golem(4, 11)
    return soak(c, 240)


@scenario("lids-corridor-chests", "lids", 360, "Chests open into a 1-wide corridor with a side pocket; golems meet head-on there and give way (traffic): no lid stays open.")
def _(c):
    x, z = c.x, c.z
    for z1, z2 in ((0, 8), (12, 20)):
        c.run(f"fill {x + 6} {Y} {z + z1} {x + 14} {Y + 2} {z + z2} minecraft:glass")
    c.run(f"fill {x + 10} {Y} {z + 9} {x + 10} {Y + 2} {z + 9} minecraft:air")  # the pocket
    # Destination chests in the corridor's north wall, opening into it.
    c.chest("logs", 8, 9)
    c.sign(8, 9, "[logs]")
    c.chest("stone", 12, 9)
    c.sign(12, 9, "[stone]")
    c.chest("copperW", 1, 10, MIXED[:4], block="waxed_copper_chest")
    c.chest("copperE", 19, 10, MIXED[5:7] + MIXED[2:4], block="waxed_copper_chest")
    for dx, dz in ((3, 10), (3, 11), (17, 10), (17, 11)):
        c.golem(dx, dz)
    return soak(c, 300)


@scenario("lids-idle-greeting", "lids", 420, "greet-cooldown-minutes: 0.25: 4 idle copper golems keep checking their empty copper chests and keep going off to greet an iron and a snow golem: no lid stays open.", alone=True)
def _(c):
    set_options(c, greet_cooldown_minutes="0.25")
    c.chest("copper", 2, 10, [("birch_log", 4), ("granite", 4)], block="waxed_copper_chest")
    c.chest("copper2", 2, 14, block="waxed_copper_chest")
    c.chest("logs", 8, 6)
    c.sign(8, 6, "[logs]")
    c.chest("stone", 8, 16)
    c.sign(8, 16, "[stone]")
    boxed(c, "iron_golem", 14, 8)
    boxed(c, "snow_golem", 14, 14)
    for dx, dz in ((4, 9), (4, 11), (4, 13), (4, 15)):
        c.golem(dx, dz)
    w = LidWatch(c, 360)
    return lambda: w.results([(len(hellos(c)) >= 1, f"{len(hellos(c))} hellos, "
                               f"{len(hellos(c, 'stopped going to say hello'))} visits given up, "
                               f"{len(hellos(c, 'has nothing to do; going to say hello'))} visits started")])


@scenario("lids-greet-after-drop", "lids", 300, "greet-cooldown-minutes: 0.25: a golem keeps carrying one log to a [logs] chest with an iron golem beside it; just after each drop it has nothing to do, but it doesn't leave to greet while the lid is up: no lid stays open.", alone=True)
def _(c):
    set_options(c, greet_cooldown_minutes="0.25")
    c.chest("copper", 2, 10, [("birch_log", 1)], block="waxed_copper_chest")
    c.chest("logs", 14, 10)
    c.sign(14, 10, "[logs]")
    boxed(c, "iron_golem", 14, 14)
    c.golem(4, 10)
    w = LidWatch(c, 240)
    x, y, z = c.chests["copper"][0]

    def check():
        if c.count("copper") == 0 and c.holding() == 0:  # delivered: another log
            c.run(f"item replace block {x} {y} {z} container.0 with minecraft:birch_log 1")
        drops = c.count("logs", "birch_log")
        return w.results([(drops >= 6, f"{drops} logs delivered (want 6+)"),
                          (len(hellos(c)) >= 2, f"{len(hellos(c))} hellos (want 2+: it still greets)")])
    return check


@scenario("lids-golem-killed-mid-open", "lids", 120, "A golem is killed while its chest lid is open: the lid closes.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 64)], block="waxed_copper_chest")
    c.chest("logs", 12, 10)
    c.sign(12, 10, "[logs]")
    c.golem(4, 10)
    w, t = LidWatch(c, 60), Trigger()
    sel = c.selector("copper_golem")

    def check():
        t.fire_when(lambda: w.sample() and w.golems,
                    lambda: c.r.cmd(f"kill {sel}"))
        return w.results([(t.at is not None, "killed the golem while it held a lid open")])
    return check


@scenario("lids-golem-frozen-mid-open", "lids", 120, "Vanilla: a golem whose AI is switched off (NoAI) while its chest lid is open keeps the lid open. This is how an interrupted visit leaves a lid up, and what greetings must not do.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 64)], block="waxed_copper_chest")
    c.chest("logs", 12, 10)
    c.sign(12, 10, "[logs]")
    c.golem(4, 10)
    w, t = LidWatch(c, 0), Trigger()
    sel = c.selector("copper_golem")

    def check():
        t.fire_when(lambda: w.sample() and w.golems,
                    lambda: c.r.cmd(f"data merge entity {sel[:-1]},limit=1] {{NoAI:1b}}"))
        w.sample()
        held = [f"{line} ({held:.0f}s; {', '.join(g)})" for held, line, g in w.found.values()]
        return [(t.at is not None, "froze the golem while it held a lid open"),
                (bool(held), f"the lid stayed open over {STUCK}s: {'; '.join(held) or 'not yet'}")]
    return check
