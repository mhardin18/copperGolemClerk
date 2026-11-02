"""edge30: edge and exception cases for the 3.0.0 features: furnace feeding (furnaces changed
under a golem, blocked output, odd fuel slots, supply and fuel containers changing), golems
dying or turning to statues mid-job, reloads mid-job, hopper-fed [Overflow] chests, and
settings at their limits.
"""
from arena import Trigger, conserved, expect, scenario
from suite_furnace import furnace, slot, supply
from suite_furnace_edge import iron_conserved, no_errors
from suite_options import set_options


def carry_room(c, supply_at=(19, 19), raw=16):
    """A [raw iron] furnace (unfuelled) far from the supply, so checks catch the golem carrying."""
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    furnace(c, "furnace", 8, 4, label="[raw iron]")
    supply(c, dx=supply_at[0], dz=supply_at[1], items=[("raw_iron", raw)])
    c.chest("rawchest", 4, 16, [("raw_iron", 1)])
    c.sign(4, 16, "[raw iron]")
    c.golem(16, 16)
    return c.abs(8, 4)


# ---- furnaces changed under a golem

@scenario("edge30-furnace-swapped-for-smoker", "edge30", 150, "The [raw iron] furnace is swapped for a smoker (it can't cook raw iron) while a golem carries raw iron to it: the iron goes back to the supply; none lost, none in the smoker.")
def _(c):
    x, y, z = carry_room(c)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.holding("raw_iron") > 0, lambda: c.r.cmd(f"setblock {x} {y} {z} minecraft:smoker[facing=south]"))
        settled = t.at is not None and t.since() > 20 and c.holding() == 0
        return [(t.at is not None, "swapped the furnace while the golem carried"), (settled, "hands empty 20s later"),
                (slot(c, "furnace", 0) == "", f"smoker input: '{slot(c, 'furnace', 0)}' (want empty)"),
                expect(c, "rawchest", "raw_iron", 1), iron_conserved(c, 17), no_errors(c)]
    return check


@scenario("edge30-furnace-relabelled", "edge30", 150, "The furnace's [raw iron] sign is changed to [sand] while a golem carries raw iron to it: the iron goes back to the supply; none lost.")
def _(c):
    carry_room(c)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.holding("raw_iron") > 0, lambda: c.sign(8, 4, "[sand]"))
        settled = t.at is not None and t.since() > 20 and c.holding() == 0
        return [(t.at is not None, "relabelled while the golem carried"), (settled, "hands empty 20s later"),
                (slot(c, "furnace", 0) == "", f"furnace input: '{slot(c, 'furnace', 0)}' (want empty)"),
                iron_conserved(c, 17), no_errors(c)]
    return check


@scenario("edge30-output-blocked", "edge30", 150, "A furnace with raw iron and coal, whose output slot holds 5 cobblestone someone left there (it can't cook): golems take the cobblestone out so it cooks again.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    furnace(c, "furnace", 8, 10, input=("raw_iron", 16), fuel=("coal", 4), result=("cobblestone", 5), label="[raw iron]")
    c.chest("cobble", 14, 16, [("cobblestone", 1)])
    c.sign(14, 16, "[cobblestone]")
    c.golem(4, 10)
    return lambda: [expect(c, "cobble", "cobblestone", 6), (c.count("furnace", "iron_ingot") > 0 or c.total("iron_ingot") > 0,
                    "it cooks again: some iron ingots made"), no_errors(c)]


@scenario("edge30-fuel-slot-full", "edge30", 60, "A furnace with 64 raw iron and 64 charcoal already in its fuel slot: golems don't bring more (the [Fuel] chest keeps all 32).")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    furnace(c, "furnace", 8, 10, input=("raw_iron", 64), fuel=("charcoal", 64), label="[raw iron]")
    c.chest("fuelchest", 14, 10, [("charcoal", 32)])
    c.sign(14, 10, "[Fuel]")
    c.golem(4, 10)
    return lambda: [(c.elapsed() > 40, "waited 40s"), expect(c, "fuelchest", "charcoal", 32), no_errors(c)]


@scenario("edge30-foreign-fuel", "edge30", 60, "A furnace burning a blaze rod (not in furnace-fuel) with raw iron waiting: golems leave its fuel alone while the blaze rod is there.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    furnace(c, "furnace", 8, 10, input=("raw_iron", 64), fuel=("blaze_rod", 2), label="[raw iron]")
    c.chest("fuelchest", 14, 10, [("charcoal", 32)])
    c.sign(14, 10, "[Fuel]")
    c.golem(4, 10)
    return lambda: [(c.elapsed() > 40, "waited 40s"), expect(c, "fuelchest", "charcoal", 32),
                    (slot(c, "furnace", 1).startswith("blaze_rod") or slot(c, "furnace", 1) == "",
                     f"fuel slot: '{slot(c, 'furnace', 1)}' (want only the blaze rod)"), no_errors(c)]


# ---- supply and fuel containers

@scenario("edge30-supply-barrel", "edge30", 120, "A barrel with a [Furnace Supply] sign: golems fetch its raw iron for the furnace.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    furnace(c, "furnace", 8, 10, fuel=("coal", 16), label="[raw iron]")
    c.chest("supply", 16, 10, [("raw_iron", 16)], block="barrel")
    c.sign(16, 10, "[Furnace Supply]")
    c.golem(4, 10)
    return lambda: [expect(c, "supply", "raw_iron", 0), iron_conserved(c, 16), no_errors(c)]


@scenario("edge30-supply-half-broken", "edge30", 150, "A double-chest [Furnace Supply]: one half (with 32 raw iron) is broken while a golem carries a load: the rest is still fed; nothing duplicated.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    furnace(c, "furnace", 8, 4, label="[raw iron]")
    c.chest("supplyA", 19, 19, [("raw_iron", 16)], half="left")
    c.chest("supplyB", 18, 19, [("raw_iron", 32)], half="right")
    c.sign(19, 19, "[Furnace Supply]")
    c.golem(16, 16)
    bx, by, bz = c.abs(18, 19)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.holding("raw_iron") > 0, lambda: c.r.cmd(f"setblock {bx} {by} {bz} minecraft:air"))
        total = c.total("raw_iron")
        return [(t.at is not None, "broke one half while the golem carried"),
                (t.at is not None and t.since() > 30, "watched for 30s"),
                (total <= 48, f"{total} raw iron exist (at most the 48 there were; the broken half's contents are gone)"),
                no_errors(c)]
    return check


@scenario("edge30-fuel-emptied-before-arrival", "edge30", 90, "The [Fuel] chest is emptied after a golem sets off for it: the golem gives up quietly; no fuel appears from nowhere.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    furnace(c, "furnace", 8, 4, input=("raw_iron", 8), label="[raw iron]")
    c.chest("fuelchest", 19, 19, [("charcoal", 8)])
    c.sign(19, 19, "[Fuel]")
    c.golem(4, 4)
    fx, fy, fz = c.abs(19, 19)
    t = Trigger()

    def check():
        t.fire_when(lambda: any("(FUEL_TAKE)" in l and c.in_cell(l) for l in c.log_lines()), lambda: c.r.cmd(f"item replace block {fx} {fy} {fz} container.0 with minecraft:air"))
        return [(t.at is not None, "emptied the [Fuel] chest after the golem set off"), (t.at is not None and t.since() > 30, "watched 30s"),
                (c.total("charcoal") == 0, f"{c.total('charcoal')} charcoal exist (want 0)"), (c.holding() == 0, "hands empty"), no_errors(c)]
    return check


# ---- golems mid-job

@scenario("edge30-statue-carrying-fuel", "edge30", 150, "A golem carrying charcoal to a furnace turns into a statue: the charcoal drops; none lost.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    furnace(c, "furnace", 4, 4, input=("raw_iron", 8), label="[raw iron]")
    c.chest("fuelchest", 19, 19, [("charcoal", 8)])
    c.sign(19, 19, "[Fuel]")
    c.golem(16, 16)
    t = Trigger()
    sel = c.selector("copper_golem")

    def check():
        t.fire_when(lambda: c.holding("charcoal") > 0,
                    lambda: c.r.cmd(f'data merge entity {sel[:-1]},limit=1] {{weather_state:"oxidized",next_weather_age:1L}}'))
        if t.at is None:
            return [(False, "waiting for the golem to carry charcoal")]
        return [(c.golems() == 0, "the golem turned into a statue"), conserved(c, "charcoal", 8), no_errors(c)]
    return check


@scenario("edge30-reload-carrying-fuel", "edge30", 150, "/cgc reload while a golem carries charcoal to a furnace: the charcoal ends up in the furnace or back in [Fuel]; none lost.", alone=True)
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    furnace(c, "furnace", 4, 4, input=("raw_iron", 8), label="[raw iron]")
    c.chest("fuelchest", 19, 19, [("charcoal", 8)])
    c.sign(19, 19, "[Fuel]")
    c.chest("overflow", 10, 18)
    c.sign(10, 18, "[Overflow]")
    c.golem(16, 16)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.holding("charcoal") > 0, lambda: c.r.cmd("coppergolemclerk reload"))
        settled = t.at is not None and t.since() > 30 and c.holding() == 0
        # The furnace cooks while this runs and burns a charcoal for every 8 items: count those as accounted for.
        burnt = -(-c.total("iron_ingot") // 8)
        have = c.total("charcoal")
        return [(t.at is not None, "reloaded while the golem carried charcoal"), (settled, "hands empty 30s later"),
                (have + burnt >= 8 and have <= 8, f"{have} charcoal left + {burnt} burnt (want 8: none lost or duplicated)"),
                expect(c, "overflow", "charcoal", 0), no_errors(c)]
    return check


@scenario("edge30-feeding-off-carrying-fuel", "edge30", 180, "furnace-feeding is turned off while a golem carries charcoal: it's sorted like anything else (into the empty chest); none lost.", alone=True)
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    furnace(c, "furnace", 4, 4, input=("raw_iron", 8), label="[raw iron]")
    c.chest("fuelchest", 19, 19, [("charcoal", 8)])
    c.sign(19, 19, "[Fuel]")
    c.chest("empty", 10, 18)
    c.golem(16, 16)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.holding("charcoal") > 0, lambda: set_options(c, furnace_feeding="false"))
        settled = t.at is not None and t.since() > 20 and c.holding() == 0
        return [(t.at is not None, "turned feeding off while the golem carried charcoal"), (settled, "hands empty"),
                conserved(c, "charcoal", 8), no_errors(c)]
    return check


# ---- hopper-fed [Overflow]

def hopper_overflow(c, items, powered=False):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    c.chest("overflow", 8, 10, items)
    c.sign(8, 10, "[Overflow]")
    c.chest("hopper", 8, 10, block="hopper", dy=1, facing="down")
    if powered:
        c.block(9, 10, "redstone_block", dy=1)
        c.cleanup.append("setblock {} {} {} minecraft:air".format(*c.abs(9, 10, 1)))
    c.chest("ingots", 14, 10, [("copper_ingot", 1), ("iron_ingot", 1)])
    c.sign(14, 10, ["[copper ingot]", "[iron ingot]"])
    c.golem(4, 10)


@scenario("edge30-overflow-locked-hopper", "edge30", 120, "overflow-fetch-wait-seconds 20: an [Overflow] chest under a redstone-locked hopper, with 3 copper ingots, still counts as hopper-fed: they're fetched after the wait, not before.", alone=True)
def _(c):
    set_options(c, overflow_fetch_wait_seconds="20")
    hopper_overflow(c, [("copper_ingot", 3)], powered=True)
    done = []

    def check():
        if not done and c.count("ingots", "copper_ingot") == 4:
            done.append(c.elapsed())
        return [(bool(done) and done[0] >= 15, f"fetched after {done[0]:.0f}s (want about 20)" if done else "not fetched yet"), no_errors(c)]
    return check


@scenario("edge30-overflow-mixed-under-min", "edge30", 150, "overflow-fetch-wait-seconds 30: a hopper-fed [Overflow] chest with 5 copper and 5 iron ingots (each under 8): neither fetched in the first 20s; both after the wait.", alone=True)
def _(c):
    set_options(c, overflow_fetch_wait_seconds="30")
    hopper_overflow(c, [("copper_ingot", 5), ("iron_ingot", 5)])
    early = []

    def check():
        if c.elapsed() < 20 and (c.count("ingots", "copper_ingot") > 1 or c.count("ingots", "iron_ingot") > 1):
            early.append(round(c.elapsed()))
        return [(not early, f"fetched early at {early[0]}s" if early else "none fetched in the first 20s"),
                expect(c, "ingots", "copper_ingot", 6), expect(c, "ingots", "iron_ingot", 6), no_errors(c)]
    return check


@scenario("edge30-overflow-full-hopper-fed", "edge30", 120, "A hopper-fed [Overflow] chest full to the brim (27 stacks of copper ingots): golems fetch from it (plenty over the minimum), no errors.")
def _(c):
    hopper_overflow(c, [("copper_ingot", 64)] * 27)
    return lambda: [(c.count("ingots", "copper_ingot") >= 17, f"ingot chest has {c.count('ingots', 'copper_ingot')} (want a full carry or more)"), no_errors(c)]


@scenario("edge30-overflow-no-home", "edge30", 60, "A hopper-fed [Overflow] chest with 20 cracked stone bricks no chest takes: left where they are, no errors.")
def _(c):
    hopper_overflow(c, [("cracked_stone_bricks", 20)])
    return lambda: [(c.elapsed() > 40, "waited 40s"), expect(c, "overflow", "cracked_stone_bricks", 20), no_errors(c)]


# ---- settings at their limits

@scenario("edge30-carry64-split64", "edge30", 120, "carry-amount 64, furnace-split-min 64, three empty [raw iron] furnaces and 64 raw iron: the whole load goes into one furnace.", alone=True)
def _(c):
    set_options(c, carry_amount="64", furnace_split_min="64")
    supply(c, dx=16, dz=10, items=[("raw_iron", 64)])
    for name, dz in (("f1", 6), ("f2", 10), ("f3", 14)):
        furnace(c, name, 8, dz, label="[raw iron]")
    c.golem(4, 10)
    counts = lambda: sorted(c.count(n, "raw_iron") for n in ("f1", "f2", "f3"))  # noqa: E731
    return lambda: [(counts() == [0, 0, 64], f"raw iron in the furnaces: {counts()} (want one with 64)"), no_errors(c)]


@scenario("edge30-extreme-trip-settings", "edge30", 120, "furnace-take-out-at 64, furnace-fuel-ahead 64, furnace-refill-below 64, furnace-lava-min-items 1000: a furnace still gets fed and fuelled, no errors.", alone=True)
def _(c):
    set_options(c, furnace_take_out_at="64", furnace_fuel_ahead="64", furnace_refill_below="64", furnace_lava_min_items="1000")
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    furnace(c, "furnace", 8, 10, label="[raw iron]")
    supply(c, dx=16, dz=10, items=[("raw_iron", 32)])
    c.chest("fuelchest", 14, 4, [("charcoal", 32)])
    c.sign(14, 4, "[Fuel]")
    c.golem(4, 10)
    return lambda: [(c.count("furnace", "raw_iron") + c.count("furnace", "iron_ingot") > 0, "the furnace got raw iron"),
                    (c.count("fuelchest", "charcoal") < 32, "and charcoal"), no_errors(c)]
