"""lava: lava bucket fuel with a cauldron that dripping lava refills (pointed dripstone under a
block with lava on top), and the backlog rule for lava (furnace-lava-min-items).

Vanilla fills such a cauldron only about every 20 minutes (random ticks), so the "fast"
scenarios raise random_tick_speed while they run (alone) and put it back after.
"""
import re

from arena import Trigger, expect, scenario
from suite_furnace import furnace, slot, supply
from suite_furnace_edge import logged, no_errors
from suite_options import set_options

FAST_TICKS = 600  # a dripstone tip gets a random tick every ~0.3s instead of ~70s: a fill every few seconds


def fast_ticks(c):
    c.run(f"gamerule random_tick_speed {FAST_TICKS}")
    c.cleanup.append("gamerule random_tick_speed 3")


def drip_cauldron(c, dx, dz, full=False):
    """A cauldron at floor level with pointed dripstone over it (one block of air between),
    hanging from a dripstone block with a lava source on top, walled in with glass."""
    c.block(dx, dz, "lava_cauldron" if full else "cauldron")
    c.block(dx, dz, "dripstone_block", dy=3)
    c.block(dx, dz, "pointed_dripstone[vertical_direction=down,thickness=tip]", dy=2)
    for ddx, ddz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        c.block(dx + ddx, dz + ddz, "glass", dy=4)
    c.block(dx, dz, "glass", dy=5)
    c.block(dx, dz, "lava", dy=4)
    c.chests.setdefault("cauldron", []).append(c.abs(dx, dz))


def cauldron_full(c):
    x, y, z = c.chests["cauldron"][0]
    return "passed" in c.r.cmd(f"execute if block {x} {y} {z} minecraft:lava_cauldron").lower()


def set_cauldron(c, block):
    x, y, z = c.chests["cauldron"][0]
    c.r.cmd(f"setblock {x} {y} {z} minecraft:{block}")


def fuel_chest(c, items=(), dx=14, dz=10):
    c.chest("fuelchest", dx, dz, list(items))
    c.sign(dx, dz, "[Fuel]")


def bucket_chest(c):
    c.chest("buckets", 14, 16, [("bucket", 1)])
    c.sign(14, 16, "[bucket]")


# ---- empty buckets wait in [Fuel] and get filled

@scenario("lava-drip-refills-buckets", "lava", 240, "Fast random ticks: 3 empty buckets in the [Fuel] chest and a dripstone cauldron: golems fill them one by one as it refills; 3 lava buckets end up in [Fuel].", alone=True)
def _(c):
    fast_ticks(c)
    drip_cauldron(c, 10, 4)
    fuel_chest(c, [("bucket", 3)])
    bucket_chest(c)
    c.golem(6, 8)
    return lambda: [expect(c, "fuelchest", "lava_bucket", 3), expect(c, "fuelchest", "bucket", 0),
                    expect(c, "buckets", "bucket", 1), no_errors(c)]


@scenario("lava-bucket-waits-in-fuel", "lava", 120, "Normal ticks, a dripstone cauldron that's still empty: the empty bucket taken out of the furnace goes into the [Fuel] chest, not the [bucket] chest.")
def _(c):
    drip_cauldron(c, 10, 4)
    furnace(c, "furnace", 8, 10, input=("raw_iron", 8), fuel=("bucket", 1), label="[raw iron]")
    fuel_chest(c)
    bucket_chest(c)
    c.golem(4, 10)
    return lambda: [expect(c, "fuelchest", "bucket", 1), expect(c, "buckets", "bucket", 1),
                    (slot(c, "furnace", 1) == "", f"fuel slot: '{slot(c, 'furnace', 1)}' (want empty)"), no_errors(c)]


@scenario("lava-full-cycle", "lava", 300, "A furnace with 64 raw iron and an empty bucket in its fuel slot, an empty dripstone cauldron, no other fuel: the bucket waits in [Fuel]; then (fast random ticks) the cauldron fills, the bucket is filled and goes back into the furnace as fuel.", alone=True)
def _(c):
    c.cleanup.append("gamerule random_tick_speed 3")
    drip_cauldron(c, 10, 4)
    furnace(c, "furnace", 8, 10, input=("raw_iron", 64), fuel=("bucket", 1), label="[raw iron]")
    fuel_chest(c)
    bucket_chest(c)
    c.golem(4, 10)
    seen = set()

    def check():
        if c.count("fuelchest", "bucket") > 0 and "waited in [Fuel]" not in seen:
            seen.add("waited in [Fuel]")
            c.r.cmd(f"gamerule random_tick_speed {FAST_TICKS}")  # now let the cauldron fill
        lit = re.search(r"data: ([1-9])", c.r.cmd("data get block {} {} {} lit_time_remaining".format(*c.chests["furnace"][0])))
        if "waited in [Fuel]" in seen and (lit or slot(c, "furnace", 1) == "lava_bucket 1"):
            seen.add("lava went back in")  # no other fuel anywhere: lit means the refilled bucket went in
        return [("waited in [Fuel]" in seen, "the empty bucket waited in the [Fuel] chest"),
                ("lava went back in" in seen, "a filled lava bucket went back into the furnace"),
                expect(c, "buckets", "bucket", 1), no_errors(c)]
    return check


# ---- when not to keep a bucket

@scenario("lava-no-drip-sorted", "lava", 120, "A plain empty cauldron (no dripstone over it): the empty bucket from the furnace is sorted into the [bucket] chest, as before.")
def _(c):
    c.block(10, 4, "cauldron")
    furnace(c, "furnace", 8, 10, input=("raw_iron", 8), fuel=("bucket", 1), label="[raw iron]")
    fuel_chest(c)
    bucket_chest(c)
    c.golem(4, 10)
    return lambda: [expect(c, "buckets", "bucket", 2), expect(c, "fuelchest", "bucket", 0), no_errors(c)]


@scenario("lava-fuel-full-sorted", "lava", 120, "A dripstone cauldron, but the [Fuel] chest is full: the empty bucket is sorted into the [bucket] chest.")
def _(c):
    drip_cauldron(c, 10, 4)
    furnace(c, "furnace", 8, 10, input=("raw_iron", 8), fuel=("bucket", 1), label="[raw iron]")
    fuel_chest(c, [("cobblestone", 64)] * 27)
    bucket_chest(c)
    c.golem(4, 10)
    return lambda: [expect(c, "buckets", "bucket", 2), no_errors(c)]


@scenario("lava-cauldron-emptied-midtrip", "lava", 150, "A full dripstone cauldron and an empty bucket in [Fuel]: the cauldron is emptied while a golem carries the bucket over; the bucket goes back to [Fuel].")
def _(c):
    drip_cauldron(c, 10, 4, full=True)
    fuel_chest(c, [("bucket", 1)], dx=18, dz=18)  # a long walk, so the check catches it carrying
    bucket_chest(c)
    c.golem(16, 16)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.holding("bucket") > 0, lambda: set_cauldron(c, "cauldron"))
        # Read the return from the log: the dripstone can refill the cauldron within seconds,
        # and then the golem takes the bucket out again before a check sees it in [Fuel].
        fx, fy, fz = c.chests["fuelchest"][0]
        returned = t.at is not None and any(f"put 1 BUCKET into the inventory of the CHEST at {fx},{fy},{fz}" in l
                                            for l in c.cell_lines())
        back = t.at is not None and t.since() > 5 and c.holding() == 0
        in_fuel = c.count("fuelchest", "bucket") + c.count("fuelchest", "lava_bucket")
        return [(t.at is not None, "emptied the cauldron while the golem carried the bucket"),
                (returned, "the golem put the empty bucket back in [Fuel]"), (back, "golem's hands empty again"),
                (in_fuel == 1, f"fuelchest has {in_fuel} bucket or lava bucket (want 1; a refilled cauldron may have filled it)"),
                expect(c, "buckets", "bucket", 1), no_errors(c)]
    return check


# ---- how far golems look for a cauldron: search-distance sideways, 8 up or down

def lava_filled(c, x, y, z):
    """The cauldron at x y z was used, and the lava bucket is in [Fuel]."""
    used = "passed" in c.r.cmd(f"execute if block {x} {y} {z} minecraft:cauldron").lower()
    return [(used, "the cauldron was emptied into the bucket" if used else "the cauldron is still full"),
            expect(c, "fuelchest", "lava_bucket", 1), expect(c, "fuelchest", "bucket", 0), no_errors(c)]


@scenario("lava-cauldron-far", "lava", 240, "search-distance: 64: the only lava cauldron is 50 blocks away (past the old 16): a golem takes the empty bucket from [Fuel] and fills it there on the first trip (the walk outlasts the 30 s no-home steps), then brings the lava back.", alone=True)
def _(c):
    from suite_distance import walkway
    set_options(c, search_distance="64")
    walkway(c)
    fuel_chest(c, [("bucket", 1)], dx=4, dz=10)
    c.block(54, 10, "lava_cauldron")
    c.golem(2, 10)
    x, y, z = c.abs(54, 10)
    return lambda: lava_filled(c, x, y, z) + [
        (not logged(c, "returned BUCKET"), "the bucket was never sent back on the way (no 'returned BUCKET')")]


@scenario("lava-cauldron-six-up", "lava", 150, "A lava cauldron at the top of a 6-step staircase, 6 blocks above the golem (past the old 4): the golem climbs up, fills the empty bucket from [Fuel] and brings the lava back.")
def _(c):
    for step in range(7):  # columns at dx 8..14; dx 13 and 14 are 6 high, a landing
        height = min(step + 1, 6)
        for dy in range(height):
            c.block(8 + step, 10, "stone", dy=dy)
    for dy in range(6):
        c.block(15, 10, "stone", dy=dy)
    c.block(15, 10, "lava_cauldron", dy=6)
    fuel_chest(c, [("bucket", 1)], dx=4, dz=12)
    c.golem(4, 10)
    x, y, z = c.abs(15, 10, 6)
    return lambda: lava_filled(c, x, y, z)


@scenario("lava-two-golems-one-cauldron", "lava", 150, "A full dripstone cauldron (normal ticks), 2 empty buckets in [Fuel] and 2 golems: only one golem goes; 1 lava bucket and 1 empty bucket end up in [Fuel], none elsewhere.")
def _(c):
    drip_cauldron(c, 10, 4, full=True)
    fuel_chest(c, [("bucket", 2)])
    bucket_chest(c)
    c.golem(6, 8)
    c.golem(6, 14)
    t = Trigger()

    def check():
        t.fire_when(lambda: not cauldron_full(c), lambda: None)
        settled = t.at is not None and t.since() > 20 and c.holding() == 0
        return [(settled, "cauldron used, then 20s with empty hands"), expect(c, "fuelchest", "lava_bucket", 1),
                expect(c, "fuelchest", "bucket", 1), expect(c, "buckets", "bucket", 1), no_errors(c)]
    return check


# ---- the backlog rule (furnace-lava-min-items 100)

def backlog_room(c, fuel_items, raw=8, supply_raw=0):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    furnace(c, "furnace", 8, 10, input=("raw_iron", raw), label="[raw iron]")
    fuel_chest(c, fuel_items)
    if supply_raw:
        supply(c, dx=16, dz=16, items=[("raw_iron", 64)] * (supply_raw // 64) + ([("raw_iron", supply_raw % 64)] if supply_raw % 64 else []))
    c.golem(4, 10)


@scenario("lava-small-backlog-charcoal", "lava", 120, "furnace-fuel lava first; 8 raw iron waiting and no supply (backlog 8): golems bring charcoal, the lava bucket stays.", alone=True)
def _(c):
    set_options(c, furnace_fuel="[lava_bucket, charcoal, coal]")
    backlog_room(c, [("lava_bucket", 1), ("charcoal", 32)])
    return lambda: [(c.count("fuelchest", "charcoal") < 32, f"charcoal left: {c.count('fuelchest', 'charcoal')} (want some taken)"),
                    expect(c, "fuelchest", "lava_bucket", 1), no_errors(c)]


@scenario("lava-big-backlog-lava", "lava", 120, "furnace-fuel lava first; 8 raw iron waiting and 128 in the [Furnace Supply] (backlog 136): golems bring the lava bucket.", alone=True)
def _(c):
    set_options(c, furnace_fuel="[lava_bucket, charcoal, coal]")
    backlog_room(c, [("lava_bucket", 1), ("charcoal", 32)], supply_raw=128)
    return lambda: [expect(c, "fuelchest", "lava_bucket", 0), no_errors(c)]


@scenario("lava-only-fuel-small-backlog", "lava", 120, "Only a lava bucket in [Fuel], 8 raw iron waiting: golems use the lava anyway (no other fuel).")
def _(c):
    backlog_room(c, [("lava_bucket", 1)])
    return lambda: [expect(c, "fuelchest", "lava_bucket", 0), no_errors(c)]


@scenario("lava-check-shows-backlog", "lava", 30, "/cgc check on a furnace with 8 raw iron and 128 in the supply says its backlog is about 136.")
def _(c):
    backlog_room(c, [("charcoal", 1)], supply_raw=128)
    x, y, z = c.abs(8, 10)

    def check():
        reply = re.sub(r"§.", "", c.r.cmd(f"cgc check {x} {y} {z}"))
        return [("Backlog: about 136 items" in reply, f"says 'Backlog: about 136 items'; got: {reply.strip()[:300]}")]
    return check


# ---- real timing

@scenario("lava-drip-normal-speed", "lava", 2700, "Manual: normal random ticks for up to 45 minutes: 2 empty buckets in [Fuel] and a dripstone cauldron: at least one is filled when the cauldron refills on its own.", manual=True)
def _(c):
    drip_cauldron(c, 10, 4)
    fuel_chest(c, [("bucket", 2)])
    bucket_chest(c)
    c.golem(6, 8)
    return lambda: [(c.count("fuelchest", "lava_bucket") >= 1, f"lava buckets in [Fuel]: {c.count('fuelchest', 'lava_bucket')} after {c.elapsed() / 60:.0f} min"),
                    expect(c, "buckets", "bucket", 1), no_errors(c)]
