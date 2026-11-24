"""cauldrons: golems keep out of cauldrons holding lava, water or powder snow (avoid-cauldrons),
and empty buckets picked up for sorting are refilled at a lava cauldron for a [Fuel] container.

A cauldron "set into the floor" replaces the platform's floor block, so its rim is level with
the floor, as in a survival sorting room where a golem burnt to death in one. Every poll counts
the golems whose feet are in a filled cauldron; a scenario fails if one was ever seen in one,
or died. Pushes are a velocity (Motion) toward the cauldron: the golem moves by physics, as when
another mob or a player bumps it.
"""
import re
import time

from arena import SIZE, Skip, Trigger, _count, expect, scenario
from suite_furnace import slot
from suite_furnace_edge import logged, no_errors
from suite_lava import drip_cauldron
from suite_modes import LUCK, VANILLA
from suite_options import set_options

FILLED = ("lava_cauldron", "water_cauldron", "powder_snow_cauldron")


def sunken(c, dx, dz, kind):
    """A cauldron in place of the floor block at (dx, dz): its rim is level with the floor."""
    c.block(dx, dz, kind, dy=-1)


def row(c, dx, kind, gap=None, dzs=range(0, SIZE)):
    """A row of sunken cauldrons across the platform at dx, leaving the floor at dz=gap."""
    for dz in dzs:
        if dz != gap:
            sunken(c, dx, dz, kind)


class Watch:
    """Golems seen in a filled cauldron, and deaths, over every poll."""

    def __init__(self, c, golems=1):
        self.c, self.golems, self.inside = c, golems, 0

    def poll(self):
        sel = self.c.selector("copper_golem")
        now = sum(_count(self.c.r.cmd(f"execute as {sel} at @s if block ~ ~ ~ minecraft:{k}")) for k in FILLED)
        self.inside = max(self.inside, now)

    def checks(self):
        died = [l for l in self.c.cell_lines() if "copper golem died" in l]
        return [(self.inside == 0, f"a golem was seen in a filled cauldron ({self.inside} at once)" if self.inside
                 else "no golem seen in a filled cauldron"),
                (not died and self.c.golems() == self.golems,
                 f"{self.c.golems()} golem(s) alive (want {self.golems}); deaths: {died[:1]}"),
                no_errors(self.c)]


def kept(c):
    return sum(1 for l in c.cell_lines() if "Kept a copper golem out of" in l)


def crossing(c, kind, gap=None):
    """Birch logs in a copper chest west of a row of sunken cauldrons, the [logs] chest east of it."""
    c.chest("copper", 2, 10, [("birch_log", 8)], block="waxed_copper_chest")
    c.chest("logs", 18, 10)
    c.sign(18, 10, "[logs]")
    row(c, 10, kind, gap=gap)
    c.golem(4, 10)


# ---- walking past them

for _kind, _what in (("water_cauldron", "water"), ("powder_snow_cauldron", "powder snow"), ("lava_cauldron", "lava")):
    def _make(kind, what):
        @scenario(f"cauldron-{kind.split('_cauldron')[0].replace('_', '-')}-row-detour", "cauldrons", 300,
                  f"A row of {what} cauldrons set into the floor across the platform, with one gap of floor near the end: "
                  "the golem takes birch logs across to the [logs] chest through the gap, never stepping into a cauldron.")
        def _(c):
            crossing(c, kind, gap=1)
            w = Watch(c)

            def check():
                w.poll()
                return [expect(c, "logs", "birch_log", 8)] + w.checks()
            return check
    _make(_kind, _what)


@scenario("cauldron-water-no-way-across", "cauldrons", 150, "A row of water cauldrons set into the floor right across the platform (no gap): the golem can't take the logs across. After 2 minutes it never stepped into one, is alive and the logs aren't in [logs].")
def _(c):
    crossing(c, "water_cauldron")
    w = Watch(c)
    t = Trigger()

    def check():
        w.poll()
        t.fire_when(lambda: True, lambda: None)
        return [(t.since() > 120, "waited 2 minutes"), expect(c, "logs", "birch_log", 0),
                (kept(c) > 0, f"logged keeping it out {kept(c)} times")] + w.checks()
    return check


# ---- pushed, falling

def push_scene(c, kind="lava_cauldron", extra=""):
    """A golem on a floor of sunken cauldrons except its own spot and a ring around the edge;
    nothing to do, so it stands about. Returns push(): set it beside the middle cauldron and
    throw it toward it."""
    for dx in range(6, 15):
        for dz in range(6, 15):
            if (dx, dz) != (9, 10):
                sunken(c, dx, dz, kind)
    x, y, z = c.abs(9, 10)
    c.run(f"summon minecraft:copper_golem {x + 0.5} {y} {z + 0.5} {{active_effects:[{LUCK}]{extra}}}")

    def push():
        sel = c.selector("copper_golem")
        c.r.cmd(f"tp {sel} {x + 0.5} {y} {z + 0.5}")
        c.r.cmd(f"data merge entity {sel.replace(']', ',limit=1]')} {{Motion:[0.45d,0.3d,0.0d]}}")
    return push


@scenario("cauldron-lava-push", "cauldrons", 60, "A golem standing in a field of lava cauldrons set into the floor is thrown toward one every 3 seconds, 10 times: it never ends up in one and doesn't burn.")
def _(c):
    push = push_scene(c)
    w = Watch(c)
    pushes = []

    def check():
        w.poll()
        if len(pushes) < 10:
            push()
            pushes.append(1)
        return [(len(pushes) >= 10, f"pushed {len(pushes)} times"), (kept(c) > 0, f"logged keeping it out {kept(c)} times")] + w.checks()
    return check


@scenario("option-avoid-cauldrons-off", "cauldrons", 60, "avoid-cauldrons: false (the control): the same pushes put the golem in a lava cauldron and it burns to death.", alone=True)
def _(c):
    set_options(c, avoid_cauldrons="false")
    push = push_scene(c)
    pushes = []

    def check():
        if len(pushes) < 10 and c.golems():
            push()
            pushes.append(1)
        died = [l for l in c.cell_lines() if "copper golem died" in l and "lava" in l]
        return [(bool(died), "the golem died in lava" if died else "the golem hasn't died"), (kept(c) == 0, "never kept out")]
    return check


@scenario("cauldron-vanilla-mode-push", "cauldrons", 60, "A golem switched to vanilla gets the same pushes: it's left alone (avoid-cauldrons is for plugin golems) and burns.")
def _(c):
    push = push_scene(c, extra="," + VANILLA)
    pushes = []

    def check():
        if len(pushes) < 10 and c.golems():
            push()
            pushes.append(1)
        died = [l for l in c.cell_lines() if "copper golem died" in l]
        return [(bool(died) or c.golems() == 0, "the vanilla golem died"), (kept(c) == 0, "never kept out")]
    return check


@scenario("cauldron-fall-into-lava", "cauldrons", 60, "A golem dropped from 4 blocks up straight over a lava cauldron set into the floor (a ring of them around it) is held above it, then set down on the nearest open floor (outside the ring), alive.")
def _(c):
    for dx in range(9, 12):
        for dz in range(9, 12):
            sunken(c, dx, dz, "lava_cauldron")
    x, y, z = c.abs(10, 10, 4)
    c.run(f"summon minecraft:copper_golem {x + 0.5} {y} {z + 0.5} {{active_effects:[{LUCK}]}}")
    w = Watch(c)

    def check():
        w.poll()
        down = logged(c, "set it down at")
        return [(down, "logged setting it down beside the cauldron")] + w.checks()
    return check


@scenario("cauldron-water-fall-in-climbs-out", "cauldrons", 60, "A golem put inside a water cauldron set into the floor (as if it filled around it) is free to climb out: it ends up on the floor, not stuck in it.")
def _(c):
    sunken(c, 10, 10, "water_cauldron")
    x, y, z = c.abs(10, 10, -1)
    c.run(f"summon minecraft:copper_golem {x + 0.5} {y + 0.25} {z + 0.5} {{active_effects:[{LUCK}]}}")
    t = Trigger()

    def check():
        t.fire_when(lambda: True, lambda: None)
        sel = c.selector("copper_golem")
        inside = _count(c.r.cmd(f"execute as {sel} at @s if block ~ ~ ~ minecraft:water_cauldron"))
        return [(t.since() > 20, "waited 20 s"), (inside == 0, "it climbed out" if inside == 0 else "still in the cauldron"),
                (c.golems() == 1, "alive"), no_errors(c)]
    return check


# ---- giving way next to them

@scenario("cauldron-give-way-corridor", "cauldrons", 360, "A one-wide corridor with lava cauldrons set into the floor on both sides (glass beyond): two golems carrying in opposite directions meet nose to nose. They sort it out without stepping into a cauldron and both deliver.")
def _(c):
    for dx in range(3, 18):
        sunken(c, dx, 9, "lava_cauldron")
        sunken(c, dx, 11, "lava_cauldron")
        c.block(dx, 8, "glass")
        c.block(dx, 8, "glass", dy=1)
        c.block(dx, 12, "glass")
        c.block(dx, 12, "glass", dy=1)
    c.chest("copperw", 1, 10, [("birch_log", 8)], block="waxed_copper_chest")
    c.chest("cobble", 1, 10, dy=1)
    c.sign(1, 10, "[cobblestone]", dy=1)
    c.chest("coppere", 19, 10, [("cobblestone", 8)], block="waxed_copper_chest")
    c.chest("logs", 19, 10, dy=1)
    c.sign(19, 10, "[logs]", dy=1)
    c.golem(3, 10)
    c.golem(17, 10)
    w = Watch(c, golems=2)

    def check():
        w.poll()
        return [expect(c, "logs", "birch_log", 8), expect(c, "cobble", "cobblestone", 8)] + w.checks()
    return check


# ---- filling buckets at a cauldron set into the floor

def fuel(c, items=(), dx=14, dz=10):
    c.chest("fuelchest", dx, dz, list(items))
    c.sign(dx, dz, "[Fuel]")


def bucket_home(c):
    c.chest("buckets", 14, 16, [("bucket", 1)])
    c.sign(14, 16, "[bucket]")


def cauldron_at(c, dx, dz, dy=0):
    c.chests.setdefault("cauldron", []).append(c.abs(dx, dz, dy))


def cauldron_is(c, block):
    x, y, z = c.chests["cauldron"][0]
    return "passed" in c.r.cmd(f"execute if block {x} {y} {z} minecraft:{block}").lower()


@scenario("cauldron-sunken-lava-refill", "cauldrons", 180, "An empty bucket in [Fuel] and a full lava cauldron set into the floor: the golem stands beside it, fills the bucket and brings the lava back, without stepping in.")
def _(c):
    sunken(c, 10, 4, "lava_cauldron")
    cauldron_at(c, 10, 4, -1)
    fuel(c, [("bucket", 1)])
    c.golem(6, 10)
    w = Watch(c)

    def check():
        w.poll()
        return [expect(c, "fuelchest", "lava_bucket", 1), expect(c, "fuelchest", "bucket", 0),
                (cauldron_is(c, "cauldron"), "the cauldron was used")] + w.checks()
    return check


# ---- empty buckets from [Overflow] and copper chests

def overflow(c, buckets):
    c.chest("overflow", 8, 4, [("bucket", buckets)])
    c.sign(8, 4, "[Overflow]")


def full_cauldron(c, dx=14, dz=4):
    c.block(dx, dz, "lava_cauldron")
    cauldron_at(c, dx, dz)


def settled(c, t, seconds=15):
    """Hands empty for this long (checked each poll)."""
    t.fire_when(lambda: c.holding() == 0 and c.elapsed() > 20, lambda: None)
    if c.holding() != 0:
        t.at = None
    return t.since() > seconds, "hands empty for a while"


@scenario("bucket-overflow-one-filled", "cauldrons", 180, "One empty bucket in [Overflow], a full lava cauldron, a [Fuel] chest and a [bucket] chest: the golem fetches the bucket, fills it and puts the lava bucket in [Fuel] (not the [bucket] chest).")
def _(c):
    overflow(c, 1)
    full_cauldron(c)
    fuel(c)
    bucket_home(c)
    c.golem(8, 10)
    w = Watch(c)
    return lambda: (w.poll(), [expect(c, "fuelchest", "lava_bucket", 1), expect(c, "overflow", "bucket", 0),
                               expect(c, "buckets", "bucket", 1), (cauldron_is(c, "cauldron"), "the cauldron was used")] + w.checks())[1]


@scenario("bucket-overflow-no-home-still-fetched", "cauldrons", 180, "As bucket-overflow-one-filled but with no [bucket] chest (no chest would take a bucket): it's fetched from [Overflow] anyway, filled and put in [Fuel].")
def _(c):
    overflow(c, 1)
    full_cauldron(c)
    fuel(c)
    c.golem(8, 10)
    return lambda: [expect(c, "fuelchest", "lava_bucket", 1), expect(c, "overflow", "bucket", 0), no_errors(c)]


@scenario("bucket-overflow-three-one-cauldron", "cauldrons", 240, "Three empty buckets in [Overflow] and one full lava cauldron (nothing refills it): the golem fetches one, fills it and puts it in [Fuel]. The other two stay in [Overflow] (no [bucket] chest, and no cauldron to wait for).")
def _(c):
    overflow(c, 3)
    full_cauldron(c)
    fuel(c)
    c.golem(8, 10)
    t = Trigger()
    return lambda: [expect(c, "fuelchest", "lava_bucket", 1), expect(c, "fuelchest", "bucket", 0),
                    expect(c, "overflow", "bucket", 2), settled(c, t), no_errors(c)]


@scenario("bucket-overflow-three-drip-waits", "cauldrons", 240, "Three empty buckets in [Overflow], a full dripstone cauldron (normal ticks: it refills only every few minutes) and a plain [Fuel] chest: one is filled; the other two go into [Fuel] to wait for the cauldron.")
def _(c):
    overflow(c, 3)
    drip_cauldron(c, 14, 4, full=True)
    fuel(c)
    c.golem(8, 10)
    t = Trigger()
    return lambda: [expect(c, "fuelchest", "lava_bucket", 1), expect(c, "fuelchest", "bucket", 2),
                    expect(c, "overflow", "bucket", 0), settled(c, t), no_errors(c)]


@scenario("bucket-overflow-no-fuel-sorted", "cauldrons", 180, "A full lava cauldron and a [bucket] chest but no [Fuel] chest: the bucket from [Overflow] is sorted into the [bucket] chest; the cauldron stays full.")
def _(c):
    overflow(c, 1)
    full_cauldron(c)
    bucket_home(c)
    c.golem(8, 10)
    t = Trigger()
    return lambda: [expect(c, "buckets", "bucket", 2), settled(c, t),
                    (cauldron_is(c, "lava_cauldron"), "the cauldron is still full"), no_errors(c)]


@scenario("bucket-overflow-fuel-full-sorted", "cauldrons", 180, "A full lava cauldron, but the [Fuel] chest is full: the bucket goes to the [bucket] chest; the cauldron stays full.")
def _(c):
    overflow(c, 1)
    full_cauldron(c)
    fuel(c, [("cobblestone", 64)] * 27)
    bucket_home(c)
    c.golem(8, 10)
    t = Trigger()
    return lambda: [expect(c, "buckets", "bucket", 2), settled(c, t),
                    (cauldron_is(c, "lava_cauldron"), "the cauldron is still full"), no_errors(c)]


@scenario("bucket-overflow-empty-cauldron-sorted", "cauldrons", 180, "A [Fuel] chest but only an empty cauldron that nothing refills: the bucket goes to the [bucket] chest, not [Fuel].")
def _(c):
    overflow(c, 1)
    c.block(14, 4, "cauldron")
    fuel(c)
    bucket_home(c)
    c.golem(8, 10)
    t = Trigger()
    return lambda: [expect(c, "buckets", "bucket", 2), expect(c, "fuelchest", "bucket", 0), settled(c, t), no_errors(c)]


@scenario("bucket-overflow-drip-waits", "cauldrons", 180, "An empty cauldron that dripping lava refills (normal ticks, so not yet full) and a [Fuel] chest: the bucket from [Overflow] waits in [Fuel], not the [bucket] chest.")
def _(c):
    overflow(c, 1)
    drip_cauldron(c, 14, 4)
    fuel(c)
    bucket_home(c)
    c.golem(8, 10)
    t = Trigger()
    return lambda: [expect(c, "fuelchest", "bucket", 1), expect(c, "buckets", "bucket", 1), settled(c, t), no_errors(c)]


@scenario("bucket-copper-chest-two", "cauldrons", 240, "Two empty buckets in a copper chest, one full lava cauldron, [Fuel] and [bucket] chests: they go to [Fuel], one is filled: [Fuel] ends with 1 lava bucket and 1 empty one.")
def _(c):
    c.chest("copper", 2, 10, [("bucket", 2)], block="waxed_copper_chest")
    full_cauldron(c)
    fuel(c)
    bucket_home(c)
    c.golem(4, 10)
    t = Trigger()
    return lambda: [expect(c, "fuelchest", "lava_bucket", 1), expect(c, "fuelchest", "bucket", 1),
                    expect(c, "buckets", "bucket", 1), expect(c, "copper", "bucket", 0), settled(c, t), no_errors(c)]


@scenario("bucket-copper-chest-one", "cauldrons", 180, "One empty bucket in a copper chest and a full lava cauldron: the golem fills it straight away and puts the lava bucket in [Fuel].")
def _(c):
    c.chest("copper", 2, 10, [("bucket", 1)], block="waxed_copper_chest")
    full_cauldron(c)
    fuel(c)
    bucket_home(c)
    c.golem(4, 10)
    return lambda: [expect(c, "fuelchest", "lava_bucket", 1), expect(c, "fuelchest", "bucket", 0),
                    expect(c, "buckets", "bucket", 1), no_errors(c)]


@scenario("option-furnace-feeding-off-buckets", "cauldrons", 180, "furnace-feeding: false: a bucket from [Overflow] is sorted into the [bucket] chest even with a full lava cauldron and a [Fuel] chest.", alone=True)
def _(c):
    set_options(c, furnace_feeding="false")
    overflow(c, 1)
    full_cauldron(c)
    fuel(c)
    bucket_home(c)
    c.golem(8, 10)
    t = Trigger()
    return lambda: [expect(c, "buckets", "bucket", 2), expect(c, "fuelchest", "lava_bucket", 0), settled(c, t),
                    (cauldron_is(c, "lava_cauldron"), "the cauldron is still full"), no_errors(c)]


# ---- a hopper-fed furnace like the survival one

def hopper_furnace(c):
    """The survival build: an unlabelled furnace with an input chest and hopper on top, a hopper
    under it emptying into an [Overflow] chest, and beside it a hopper feeding its fuel slot from
    a [Fuel] chest (which sits on the overflow chest). Two full lava cauldrons set into the floor."""
    c.block(10, 10, "hopper[facing=south]")
    c.block(10, 10, "furnace[facing=east]", dy=1)
    c.block(10, 10, "hopper[facing=down]", dy=2)
    c.chest("input", 10, 10, [("raw_iron", 64)], dy=3, facing="east")
    c.chest("overflow", 10, 11, facing="east", half="left")
    c.chest("overflow", 10, 12, facing="east", half="right")
    # As in survival: the [Overflow] sign stands on the half without the hopper on it.
    c.run('setblock {} {} {} minecraft:oak_sign[rotation=4]{{front_text:{{messages:["[Overflow]","","",""]}}}}'.format(*c.abs(10, 12, 1)))
    c.block(10, 11, "hopper[facing=north]", dy=1)
    c.chest("fuelchest", 10, 11, dy=2, facing="east")
    c.run('setblock {} {} {} minecraft:oak_wall_sign[facing=east]{{front_text:{{messages:["[Fuel]","","",""]}}}}'.format(*c.abs(11, 11, 2)))
    for dz in (6, 7):
        sunken(c, 10, dz, "lava_cauldron")
    c.chests["cauldrons"] = [c.abs(10, 6, -1), c.abs(10, 7, -1)]
    fx, fy, fz = c.abs(10, 10, 1)
    c.chests["furnace"] = [(fx, fy, fz)]


def buckets_anywhere(c, item):
    """Empty buckets or lava buckets in the chests, hoppers, the furnace, hands and on the ground."""
    hoppers = [c.abs(10, 10), c.abs(10, 10, 2), c.abs(10, 11, 1)]
    n = c.total(item)
    for x, y, z in hoppers:
        n += _count(c.r.cmd(f"execute if items block {x} {y} {z} container.* minecraft:{item}"))
    return n


def used_cauldrons(c):
    return sum(1 for x, y, z in c.chests["cauldrons"]
               if "passed" in c.r.cmd(f"execute if block {x} {y} {z} minecraft:cauldron").lower())


@scenario("bucket-hopper-furnace", "cauldrons", 300, "deliver-reach: 4, as on survival (the [Fuel] chest on the fuel hopper is row 3). The survival smelter: an empty bucket in the furnace's fuel slot drops through the bottom hopper into [Overflow]; the golem fetches it, fills it at a lava cauldron set into the floor and puts it in [Fuel]; the fuel hopper feeds it back and the furnace lights. One bucket all along, never in a cauldron.", alone=True)
def _(c):
    set_options(c, deliver_reach="4")
    hopper_furnace(c)
    fx, fy, fz = c.chests["furnace"][0]
    c.run(f"item replace block {fx} {fy} {fz} container.1 with minecraft:bucket")
    c.golem(6, 10)
    w = Watch(c)
    seen = set()

    def check():
        w.poll()
        lit = "data: 0" not in c.r.cmd(f"data get block {fx} {fy} {fz} lit_time_remaining")
        if lit or slot(c, "furnace", 1).startswith("lava_bucket"):
            seen.add("lit")
        total = buckets_anywhere(c, "bucket") + buckets_anywhere(c, "lava_bucket")
        return [("lit" in seen, "the furnace got the lava back and lit"), (used_cauldrons(c) >= 1, f"{used_cauldrons(c)} cauldron(s) used"),
                (total == 1 or "lit" in seen and total == 0, f"{total} bucket(s) in all (want 1)")] + w.checks()
    return check


@scenario("bucket-hopper-furnace-two", "cauldrons", 360, "The survival smelter with two empty buckets (one in the fuel slot, one already in [Overflow]): both are filled at the two lava cauldrons and the furnace lights; two buckets all along. Checks that the empties don't just go round (fuel hopper -> furnace -> bottom hopper -> [Overflow]) for ever. deliver-reach: 4, as on survival.", alone=True)
def _(c):
    set_options(c, deliver_reach="4")
    hopper_furnace(c)
    fx, fy, fz = c.chests["furnace"][0]
    c.run(f"item replace block {fx} {fy} {fz} container.1 with minecraft:bucket")
    ox, oy, oz = c.chests["overflow"][0]
    c.run(f"item replace block {ox} {oy} {oz} container.0 with minecraft:bucket")
    c.golem(6, 10)
    w = Watch(c)

    def check():
        w.poll()
        total = buckets_anywhere(c, "bucket") + buckets_anywhere(c, "lava_bucket")
        lit = "data: 0" not in c.r.cmd(f"data get block {fx} {fy} {fz} lit_time_remaining")
        # Once the furnace burns a lava bucket, its empty bucket comes round again (and waits
        # in [Overflow] here, with no dripstone to refill the cauldrons): two buckets all along.
        return [(used_cauldrons(c) == 2, f"{used_cauldrons(c)} of 2 cauldrons used"), (lit, "the furnace is lit"),
                (total == 2, f"{total} buckets and lava buckets in all (want 2)")] + w.checks()
    return check


@scenario("bucket-overflow-high-bucket-chest", "cauldrons", 180, "deliver-reach: 4 and the only [bucket] chest at the top of a 4-high stack (the survival room): the bucket from [Overflow] is still filled for [Fuel], not carried up to the [bucket] chest.", alone=True)
def _(c):
    set_options(c, deliver_reach="4")
    overflow(c, 1)
    full_cauldron(c)
    fuel(c)
    for dy in range(3):
        c.chest(f"stack{dy}", 14, 16, [("dirt", 1)], dy=dy)
        c.sign(14, 16, "[dirt]", dy=dy)
    c.chest("buckets", 14, 16, [("bucket", 1)], dy=3)
    c.sign(14, 16, "[bucket]", dy=3)
    c.golem(8, 10)
    return lambda: [expect(c, "fuelchest", "lava_bucket", 1), expect(c, "buckets", "bucket", 1),
                    expect(c, "overflow", "bucket", 0), no_errors(c)]


@scenario("bucket-hopper-fuel-waits-in-overflow", "cauldrons", 150, "The survival smelter, its cauldrons empty but dripstone over them (normal ticks), a [bucket] chest, and an empty bucket in [Overflow]: the only [Fuel] chest feeds the furnace, so the bucket waits in [Overflow] for a cauldron to fill; it isn't sorted into the [bucket] chest or put in [Fuel]. deliver-reach: 4.", alone=True)
def _(c):
    set_options(c, deliver_reach="4")
    hopper_furnace(c)
    for dz in (6, 7):
        sunken(c, 10, dz, "cauldron")
        c.block(10, dz, "dripstone_block", dy=3)
        c.block(10, dz, "pointed_dripstone[vertical_direction=down,thickness=tip]", dy=2)
        c.block(10, dz, "lava", dy=4)
    for dx, dz in ((9, 6), (11, 6), (9, 7), (11, 7), (10, 5), (10, 8)):
        c.block(dx, dz, "glass", dy=4)
    ox, oy, oz = c.chests["overflow"][1]
    c.run(f"item replace block {ox} {oy} {oz} container.0 with minecraft:bucket")
    bucket_home(c)
    c.golem(6, 10)
    t = Trigger()

    def check():
        t.fire_when(lambda: True, lambda: None)
        return [(t.since() > 90, "waited 90 s"), expect(c, "overflow", "bucket", 1), expect(c, "buckets", "bucket", 1),
                expect(c, "fuelchest", "bucket", 0), no_errors(c)]
    return check


# ---- an idle golem near a cauldron in a nook (survival's dripstone lava farm, 2026-11-24)

def nook(c, dx=8, dz=8):
    """Three cauldrons sunk into the floor (empty, lava, empty) under pointed dripstone, in a
    1-block-high nook walled on three sides and open to the south (dz + 1)."""
    for i, kind in enumerate(("cauldron", "lava_cauldron", "cauldron")):
        x = dx + i
        sunken(c, x, dz, kind)
        c.block(x, dz, "pointed_dripstone[vertical_direction=down,thickness=tip]", dy=1)
        c.block(x, dz, "stone", dy=2)
        c.block(x, dz - 1, "stone")
        c.block(x, dz - 1, "stone", dy=1)
    for x in (dx - 1, dx + 3):
        for dy in (0, 1, 2):
            c.block(x, dz, "stone", dy=dy)


class Wander:
    """Every 5 s, send the platform's golems toward a spot with GolemTestHooks' /golemwalk: the
    golem's own path, as vanilla's wandering picks one (not a CopperGolemClerk job)."""

    def __init__(self, c, spots):
        """spots: (dx, dz, dy) to cycle through, one per walk."""
        self.c = c
        x1, y1, z1 = c.abs(-2, -2, -1)
        x2, y2, z2 = c.abs(SIZE + 2, SIZE + 2, 4)
        self.cmds = []
        for dx, dz, dy in spots:
            x, y, z = c.abs(dx, dz, dy)
            self.cmds.append(f"golemwalk {x1} {y1} {z1} {x2} {y2} {z2} {x + 0.5} {y} {z + 0.5}")
        if "Unknown" in c.r.cmd(self.cmds[0]) or "Usage" in c.r.cmd(self.cmds[0]):
            raise Skip("needs GolemTestHooks with /golemwalk on pluginDev")
        self.sent, self.refused, self.last, self.n = 0, 0, 0, 0

    def poll(self):
        if time.time() - self.last >= 5:
            self.last = time.time()
            cmd = self.cmds[self.n % len(self.cmds)]
            self.n += 1
            m = re.search(r"walk sent (\d+) refused (\d+)", self.c.r.cmd(cmd))
            if m:
                self.sent += int(m.group(1))
                self.refused += int(m.group(2))


def kept_away(c):
    return sum(1 for l in c.cell_lines() if "Kept an idle copper golem from going back" in l)


def shove(c, dx, dz, mx):
    """Throw the platform's golem sideways (Motion mx along x) from (dx, dz), as a bump does."""
    sel = c.selector("copper_golem")
    x, y, z = c.abs(dx, dz)
    c.r.cmd(f"tp {sel} {x + 0.5} {y} {z + 0.5}")
    c.r.cmd(f"data merge entity {sel.replace(']', ',limit=1]')} {{Motion:[{mx}d,0.2d,0.0d]}}")


def nook_scene(c, holding=None):
    """The nook, a home copper chest with nothing to sort, a golem in the nook on the empty
    cauldron beside the lava one, thrown toward the lava once (the guard turns it back), then
    sent back over the cauldrons every 5 s as vanilla's wandering did on survival."""
    nook(c)
    c.chest("copper", 4, 14, block="waxed_copper_chest")
    c.golem(8, 9)
    if holding:
        c.run(f"item replace entity {c.selector('copper_golem')} weapon.mainhand with minecraft:{holding}")
    state = {"last": 0.0}
    walk = Wander(c, [(9, 8, 1), (8, 8, 1), (10, 8, 1), (9, 9, 0)])  # survival's targets: over and beside the lava

    def poll():
        if kept(c) == 0:  # a throw doesn't always reach the rim: again every 3 s until the guard turns it back
            if c.elapsed() >= 5 and time.time() - state["last"] >= 3:
                shove(c, 8, 8, 0.45)  # toward the lava cauldron at (9, 8)
                state["last"] = time.time()
        else:
            walk.poll()
    return walk, poll


@scenario("cauldron-nook-idle-keeps-away", "cauldrons", 120, "An idle golem in a 1-high nook with a lava cauldron sunk into its floor (survival's dripstone farm) is thrown toward the lava (the guard turns it back), then sent back over and beside the cauldron every 5 seconds, as vanilla's wandering did: those paths are refused for 30 seconds; never in a cauldron, alive.", alone=True)
def _(c):
    w = Watch(c)
    walk, poll = nook_scene(c)

    def check():
        w.poll()
        poll()
        return [(walk.n >= 4, f"{walk.n} walks after the turn-back (want 4+)"),
                (kept(c) >= 1, f"turned back at the lava {kept(c)} times (want 1+)"),
                (kept_away(c) >= 3, f"its paths back to the cauldron were refused {kept_away(c)} times (want 3+);"
                 f" walks sent {walk.sent}, refused {walk.refused}")] + w.checks()
    return check


@scenario("cauldron-nook-carrying-not-held-back", "cauldrons", 120, "The same nook and throw with the golem holding a stone (not idle): its paths toward the cauldron aren't refused (keep-away is only for idle golems); never in a cauldron, alive.", alone=True)
def _(c):
    w = Watch(c)
    walk, poll = nook_scene(c, holding="stone")

    def check():
        w.poll()
        poll()
        lines = c.cell_lines()
        put_back = next((i for i, l in enumerate(lines) if "returned STONE" in l), len(lines))
        while_holding = sum(1 for l in lines[:put_back] if "Kept an idle copper golem from going back" in l)
        return [(walk.n >= 4, f"{walk.n} walks after the turn-back (want 4+)"),
                (kept(c) >= 1, f"turned back at the lava {kept(c)} times (want 1+)"),
                (walk.sent >= 2, f"{walk.sent} walks sent"),
                (while_holding == 0, f"paths refused {while_holding} times while it held the stone (want 0: it's"
                 f" carrying something){'; put the stone back, idle after' if put_back < len(lines) else ''}")] + w.checks()
    return check
