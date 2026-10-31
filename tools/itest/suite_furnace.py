"""furnace: golems feed, empty and fuel furnaces (furnace-feeding).

Labelled furnaces and hopper lines (hoppers leading into a furnace's top or side) take part.
Items to cook come only from [Furnace Supply] containers; fuel only from [Fuel] containers.

Furnace slots: container.0 = input, container.1 = fuel, container.2 = result.
"""
import re

from arena import Trigger, expect, scenario
from suite_claims import PLACE_A, PLACE_B, need_fixture
from suite_options import set_options

WORLD = "pluginDev_world"


def furnace(c, name, dx, dz, block="furnace", input=None, fuel=None, result=None, label=None, dy=0):
    x, y, z = c.abs(dx, dz, dy)
    c.run(f"setblock {x} {y} {z} minecraft:{block}[facing=south]")
    for slot, stack in ((0, input), (1, fuel), (2, result)):
        if stack:
            c.run(f"item replace block {x} {y} {z} container.{slot} with minecraft:{stack[0]} {stack[1]}")
    c.chests.setdefault(name, []).append((x, y, z))
    if label:
        c.sign(dx, dz, label, dy=dy)


def hopper(c, name, dx, dz, facing, dy=1, items=()):
    """A hopper at height dy (1 = on top of a floor-level furnace), facing down or sideways."""
    c.chest(name, dx, dz, items, block="hopper", dy=dy, facing=facing)


def slot(c, name, n):
    """'item count' in a furnace slot, or '' if empty."""
    x, y, z = c.chests[name][0]
    out = c.r.cmd(f"data get block {x} {y} {z} Items")
    m = re.search(r'\{Slot: ' + str(n) + r'b, id: "minecraft:([a-z_]+)", count: (\d+)', out)
    return f"{m.group(1)} {m.group(2)}" if m else ""


def cooked(c, name):
    """Raw iron waiting in a furnace plus iron ingots made there (still in it)."""
    return c.count(name, "raw_iron") + c.count(name, "iron_ingot")


def supply(c, dx=16, dz=10, items=(("raw_iron", 16),), name="supply"):
    c.chest(name, dx, dz, list(items))
    c.sign(dx, dz, "[Furnace Supply]")


# ---- where items to cook come from

@scenario("furnace-feed-from-supply-copper-chest", "furnace", 150, "A copper chest with a [Furnace Supply] sign: the golem takes its raw iron to the blast furnace labelled [raw iron], not the raw iron chest.")
def _(c):
    c.chest("copper", 2, 10, [("raw_iron", 16)], block="waxed_copper_chest")
    c.sign(2, 10, "[Furnace Supply]")
    furnace(c, "blast", 10, 10, block="blast_furnace", fuel=("coal", 16), label="[raw iron]")
    c.chest("raw", 14, 10, [("raw_iron", 1)])
    c.golem(4, 10)
    # Ingots may already have been taken out again by the idle golem: count them wherever they are.
    return lambda: [(c.count("blast", "raw_iron") + c.total("iron_ingot") == 16,
                     f"{c.count('blast', 'raw_iron')} raw iron in the furnace + {c.total('iron_ingot')} ingots (want 16 in all)"),
                    expect(c, "raw", "raw_iron", 1), expect(c, "copper", "raw_iron", 0)]


@scenario("furnace-not-from-plain-copper-chest", "furnace", 150, "A copper chest without a [Furnace Supply] sign: its raw iron is sorted into the raw iron chest, never into the labelled blast furnace.")
def _(c):
    c.chest("copper", 2, 10, [("raw_iron", 16)], block="waxed_copper_chest")
    furnace(c, "blast", 10, 10, block="blast_furnace", fuel=("coal", 16), label="[raw iron]")
    c.chest("raw", 14, 10, [("raw_iron", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "raw", "raw_iron", 17), expect(c, "blast", "raw_iron", 0)]


@scenario("furnace-fetch-from-supply-chest", "furnace", 150, "An idle golem fetches raw iron from a [Furnace Supply] chest and puts it in the labelled blast furnace.")
def _(c):
    supply(c)
    furnace(c, "blast", 8, 10, block="blast_furnace", fuel=("coal", 16), label="[raw iron]")
    c.chest("ingots", 4, 16, [("iron_ingot", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "supply", "raw_iron", 0),
                    (cooked(c, "blast") + c.total("iron_ingot") - 1 == 16,
                     f"{cooked(c, 'blast')} raw/ingots in the furnace, {c.total('iron_ingot') - 1} ingots made in all (want 16 raw iron used)")]


@scenario("furnace-split-between-furnaces", "furnace", 150, "16 raw iron from the supply and two blast furnaces labelled [raw iron]: the load is split so both get some.")
def _(c):
    supply(c)
    furnace(c, "blast1", 8, 6, block="blast_furnace", fuel=("coal", 16), label="[raw iron]")
    furnace(c, "blast2", 8, 14, block="blast_furnace", fuel=("coal", 16), label="[raw iron]")
    seen = set()

    def check():
        for name in ("blast1", "blast2"):
            if cooked(c, name) > 0:
                seen.add(name)
        return [(seen == {"blast1", "blast2"}, f"furnaces that got raw iron: {sorted(seen)} (want both)")]
    c.golem(4, 10)
    return check


@scenario("furnace-unlabelled", "furnace", 150, "An unlabelled furnace (no hopper line) is left alone: the supply's raw iron stays put.")
def _(c):
    supply(c)
    furnace(c, "furnace", 8, 10, fuel=("coal", 16))
    c.golem(4, 10)
    return lambda: [(c.elapsed() > 40, "waited 40s"), expect(c, "supply", "raw_iron", 16), expect(c, "furnace", "raw_iron", 0)]


@scenario("furnace-cant-cook", "furnace", 150, "A smoker labelled [raw iron] can't cook it: golems never fetch the supply's raw iron for it.")
def _(c):
    supply(c)
    furnace(c, "smoker", 8, 10, block="smoker", fuel=("coal", 16), label="[raw iron]")
    c.golem(4, 10)
    return lambda: [(c.elapsed() > 40, "waited 40s"), expect(c, "supply", "raw_iron", 16), expect(c, "smoker", "raw_iron", 0)]


@scenario("supply-chest-not-a-destination", "furnace", 150, "An empty [Furnace Supply] chest never gets sorted items: a golem with dirt (no dirt chest) uses the other empty chest.")
def _(c):
    c.chest("copper", 2, 10, [("dirt", 16)], block="waxed_copper_chest")
    c.chest("supply", 6, 10)
    c.sign(6, 10, "[Furnace Supply]")
    c.chest("empty", 16, 10)
    c.golem(4, 10)
    return lambda: [expect(c, "empty", "dirt", 16), expect(c, "supply", "dirt", 0)]


# ---- outputs

@scenario("furnace-empty", "furnace", 150, "An idle golem takes the iron ingots out of a labelled furnace and sorts them into the ingot chest.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    furnace(c, "furnace", 8, 10, result=("iron_ingot", 10), label="[raw iron]")
    c.chest("ingots", 14, 10, [("iron_ingot", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "ingots", "iron_ingot", 11), expect(c, "furnace", "iron_ingot", 0)]


@scenario("furnace-collected-by-hopper", "furnace", 60, "A labelled furnace with a (locked by redstone) hopper under it: golems leave its ingots to the hopper.")
def _(c):
    furnace(c, "furnace", 8, 10, result=("iron_ingot", 10), label="[raw iron]")
    hopper(c, "under", 8, 10, "down", dy=-1)
    c.block(9, 10, "redstone_block", dy=-1)  # a powered hopper doesn't pull, so the ingots stay in the furnace
    c.cleanup.append("setblock {} {} {} minecraft:air".format(*c.abs(9, 10, -1)))
    c.chest("ingots", 14, 10, [("iron_ingot", 1)])
    c.golem(4, 10)
    return lambda: [(c.elapsed() > 40, "waited 40s"), expect(c, "ingots", "iron_ingot", 1), expect(c, "furnace", "iron_ingot", 10)]


@scenario("furnace-no-loop", "furnace", 150, "Stone taken out of a furnace labelled [cobblestone] [stone] goes to the stone chest, not back into the furnace.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    furnace(c, "furnace", 8, 10, result=("stone", 8), label=["[cobblestone]", "[stone]"])
    c.chest("stone", 14, 10, [("stone", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "stone", "stone", 9), (slot(c, "furnace", 0) == "", f"furnace input: '{slot(c, 'furnace', 0)}' (want empty)")]


# ---- hopper lines

@scenario("line-feeds-unlabelled-furnace", "furnace", 150, "A hopper on top of an unlabelled furnace: golems put the supply's raw iron into the hopper and it cooks.")
def _(c):
    supply(c)
    furnace(c, "furnace", 8, 10, fuel=("coal", 16))
    hopper(c, "hopper", 8, 10, "down")
    c.chest("ingots", 4, 16, [("iron_ingot", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "supply", "raw_iron", 0),
                    (cooked(c, "furnace") + c.count("hopper", "raw_iron") + c.total("iron_ingot") - 1 == 16,
                     f"furnace {cooked(c, 'furnace')}, hopper {c.count('hopper', 'raw_iron')}, ingots {c.total('iron_ingot') - 1} (want 16 in all)")]


@scenario("line-feeds-two-furnaces", "furnace", 180, "One hopper line over two furnaces sunk into the floor (a top hopper that pushes on and is pulled from): one delivery into its head feeds both.")
def _(c):
    supply(c)
    furnace(c, "f1", 8, 10, fuel=("coal", 16), dy=-1)
    furnace(c, "f2", 10, 10, fuel=("coal", 16), dy=-1)
    hopper(c, "head", 8, 10, "east", dy=1)     # head: pushes east...
    hopper(c, "mid", 9, 10, "east", dy=1)
    hopper(c, "end", 10, 10, "down", dy=1)
    hopper(c, "f2top", 10, 10, "down", dy=0)   # ...into f2
    hopper(c, "f1top", 8, 10, "down", dy=0)    # and is pulled from by the hopper on f1
    seen = set()

    def check():
        for name in ("f1", "f2"):
            if cooked(c, name) > 0:
                seen.add(name)
        return [(seen == {"f1", "f2"}, f"furnaces that got raw iron: {sorted(seen)} (want both)"),
                expect(c, "supply", "raw_iron", 0)]
    c.golem(4, 10)
    return check


@scenario("line-never-clogs", "furnace", 60, "A hopper line still holding sand (its furnace is full): golems don't add the supply's raw iron to it.")
def _(c):
    supply(c)
    furnace(c, "furnace", 8, 10, input=("sand", 64))   # full and unfuelled: the hopper can't push
    hopper(c, "hopper", 8, 10, "down", items=[("sand", 5)])
    c.golem(4, 10)
    return lambda: [(c.elapsed() > 40, "waited 40s"), expect(c, "supply", "raw_iron", 16), expect(c, "hopper", "raw_iron", 0)]


@scenario("line-takes-only-what-all-can-cook", "furnace", 60, "A hopper line into a smoker: golems never put the supply's raw iron into it (a smoker can't cook it).")
def _(c):
    supply(c)
    furnace(c, "smoker", 8, 10, block="smoker", fuel=("coal", 16))
    hopper(c, "hopper", 8, 10, "down")
    c.golem(4, 10)
    return lambda: [(c.elapsed() > 40, "waited 40s"), expect(c, "supply", "raw_iron", 16), expect(c, "hopper", "raw_iron", 0)]


@scenario("line-feeder-chest-not-sorted-into", "furnace", 150, "An empty chest on a hopper that feeds a furnace (a hand-built smelter): a golem with dirt uses the other empty chest, never the smelter's.")
def _(c):
    c.chest("copper", 2, 10, [("dirt", 16)], block="waxed_copper_chest")
    furnace(c, "furnace", 8, 10)
    hopper(c, "hopper", 8, 10, "down")
    c.chest("feeder", 8, 10, dy=2)
    c.chest("empty", 16, 10)
    c.golem(4, 10)
    return lambda: [expect(c, "empty", "dirt", 16), expect(c, "feeder", "dirt", 0)]


@scenario("line-feeder-chest-filled-from-supply", "furnace", 150, "A chest on top of a smelter's hopper is the line's head: golems put the supply's raw iron into it.")
def _(c):
    supply(c)
    furnace(c, "furnace", 8, 10, fuel=("coal", 16))
    hopper(c, "hopper", 8, 10, "down")
    c.chest("feeder", 8, 10, dy=2)
    c.chest("ingots", 4, 16, [("iron_ingot", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "supply", "raw_iron", 0),
                    (cooked(c, "furnace") + c.count("feeder", "raw_iron") + c.count("hopper", "raw_iron") + c.total("iron_ingot") - 1 == 16,
                     f"furnace {cooked(c, 'furnace')}, feeder {c.count('feeder', 'raw_iron')}, ingots {c.total('iron_ingot') - 1} (want 16 in all)")]


@scenario("line-fuel-just-enough", "furnace", 150, "8 raw iron in an unlabelled furnace with a hopper into its side: golems put exactly 1 charcoal from the [Fuel] chest into that hopper.")
def _(c):
    furnace(c, "furnace", 8, 10, input=("raw_iron", 8))
    hopper(c, "side", 9, 10, "west", dy=0)
    c.chest("fuelchest", 14, 14, [("charcoal", 32)])
    c.sign(14, 14, "[Fuel]")
    c.golem(4, 10)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.count("fuelchest", "charcoal") < 32, lambda: None)
        settled = t.at is not None and t.since() > 15 and c.holding("charcoal") == 0
        return [(settled, "fuel delivered and the golem's hands empty for 15s"), expect(c, "fuelchest", "charcoal", 31)]
    return check


@scenario("option-furnace-empty-hopper-lines-off", "furnace", 60, "furnace-empty-hopper-lines: false: an unlabelled furnace on a hopper line keeps its ingots.", alone=True)
def _(c):
    set_options(c, furnace_empty_hopper_lines="false")
    furnace(c, "furnace", 8, 10, result=("iron_ingot", 10))
    hopper(c, "hopper", 8, 10, "down")
    c.chest("ingots", 14, 10, [("iron_ingot", 1)])
    c.golem(4, 10)
    return lambda: [(c.elapsed() > 40, "waited 40s"), expect(c, "ingots", "iron_ingot", 1), expect(c, "furnace", "iron_ingot", 10)]


@scenario("line-furnace-emptied", "furnace", 150, "An unlabelled furnace on a hopper line (no hopper under it): golems take out its ingots and sort them.")
def _(c):
    furnace(c, "furnace", 8, 10, result=("iron_ingot", 10))
    hopper(c, "hopper", 8, 10, "down")
    c.chest("ingots", 14, 10, [("iron_ingot", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "ingots", "iron_ingot", 11), expect(c, "furnace", "iron_ingot", 0)]


@scenario("check-hopper-report", "furnace", 30, "/cgc check on a smelter's hopper: which furnace it feeds, through the top, and what golems put in.")
def _(c):
    furnace(c, "furnace", 8, 10, block="blast_furnace")
    hopper(c, "hopper", 8, 10, "down")
    x, y, z = c.abs(8, 10, 1)

    def check():
        reply = re.sub(r"§.", "", c.r.cmd(f"cgc check {x} {y} {z}"))
        def has(t):
            return t in reply, f"says '{t}'" + ("" if t in reply else f"; got: {reply.strip()[:400]}")
        return [has("It's the head of its line"), has("Feeds the top (items to cook) of: blast furnace at"),
                has("Golems put in items from [Furnace Supply] containers")]
    return check


# ---- settings

@scenario("option-furnace-feeding-off", "furnace", 60, "furnace-feeding: false: the supply's raw iron is never fetched for the labelled furnace.", alone=True)
def _(c):
    set_options(c, furnace_feeding="false")
    supply(c)
    furnace(c, "furnace", 8, 10, fuel=("coal", 16), label="[raw iron]")
    c.golem(4, 10)
    return lambda: [(c.elapsed() > 40, "waited 40s"), expect(c, "supply", "raw_iron", 16), expect(c, "furnace", "raw_iron", 0)]


@scenario("furnace-fuel-bad-names", "furnace", 30, "furnace-fuel with a made-up name and an item that doesn't burn: a warning for each.", alone=True)
def _(c):
    set_options(c, furnace_fuel="[coal, frobnitz, dirt]")
    def check():
        log = "\n".join(c.log_lines())
        return [("furnace-fuel: 'frobnitz' isn't an item" in log, "warned about frobnitz"),
                ("furnace-fuel: dirt doesn't burn" in log, "warned about dirt")]
    return check


# ---- fuel: only from [Fuel] containers, in furnace-fuel order, just enough

def fuel_setup(c, fuel_chest_items, raw=8, block="furnace", slot_fuel=None, fuel_label="[Fuel]"):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    furnace(c, "furnace", 8, 10, block=block, input=("raw_iron", raw), fuel=slot_fuel, label="[raw iron]")
    c.chest("fuelchest", 14, 10, fuel_chest_items)
    if fuel_label:
        c.sign(14, 10, fuel_label)
    c.golem(4, 10)


@scenario("fuel-not-from-other-chests", "furnace", 60, "Coal in a chest without a [Fuel] sign is never used: after 40 seconds the furnace has no fuel and the coal is all there.")
def _(c):
    fuel_setup(c, [("coal", 32)], fuel_label=None)
    return lambda: [(c.elapsed() > 40, "waited 40s"), expect(c, "fuelchest", "coal", 32),
                    (slot(c, "furnace", 1) == "", f"fuel slot: '{slot(c, 'furnace', 1)}' (want empty)")]


@scenario("fuel-charcoal-before-coal", "furnace", 150, "A [Fuel] chest with coal and charcoal: golems bring charcoal (first in furnace-fuel), and the coal isn't touched.")
def _(c):
    fuel_setup(c, [("coal", 32), ("charcoal", 32)])
    return lambda: [(c.count("fuelchest", "charcoal") < 32, f"charcoal left: {c.count('fuelchest', 'charcoal')} (want fewer than 32)"),
                    expect(c, "fuelchest", "coal", 32)]


@scenario("fuel-just-enough", "furnace", 150, "8 raw iron waiting: golems bring exactly 1 charcoal (it cooks 8), not a whole load.")
def _(c):
    fuel_setup(c, [("charcoal", 32)])
    t = Trigger()

    def check():
        t.fire_when(lambda: c.count("fuelchest", "charcoal") < 32, lambda: None)
        settled = t.at is not None and t.since() > 15 and c.holding("charcoal") == 0
        return [(settled, "fuel delivered and the golem's hands empty for 15s"),
                expect(c, "fuelchest", "charcoal", 31)]
    return check


@scenario("fuel-just-enough-20", "furnace", 150, "20 raw iron waiting: golems bring 3 charcoal (8 + 8 + 4).")
def _(c):
    fuel_setup(c, [("charcoal", 32)], raw=20)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.count("fuelchest", "charcoal") < 32, lambda: None)
        settled = t.at is not None and t.since() > 15 and c.holding("charcoal") == 0
        return [(settled, "fuel delivered and the golem's hands empty for 15s"),
                expect(c, "fuelchest", "charcoal", 29)]
    return check


@scenario("fuel-coal-burns-out-first", "furnace", 240, "A blast furnace burning its last coal with more raw iron waiting: golems don't add coal, they wait and then bring charcoal.")
def _(c):
    fuel_setup(c, [("coal", 32), ("charcoal", 32)], raw=24, block="blast_furnace", slot_fuel=("coal", 1))
    return lambda: [expect(c, "fuelchest", "coal", 32),
                    (c.count("fuelchest", "charcoal") < 32, f"charcoal left: {c.count('fuelchest', 'charcoal')} (want fewer than 32: brought once the coal ran out)")]


@scenario("fuel-chest-not-a-destination", "furnace", 150, "An empty [Fuel] chest never gets sorted items: a golem with dirt (no dirt chest) uses the other empty chest.")
def _(c):
    c.chest("copper", 2, 10, [("dirt", 16)], block="waxed_copper_chest")
    c.chest("fuelchest", 6, 10)
    c.sign(6, 10, "[Fuel]")
    c.chest("empty", 16, 10)
    c.golem(4, 10)
    return lambda: [expect(c, "empty", "dirt", 16), expect(c, "fuelchest", "dirt", 0)]


@scenario("fuel-chest-with-label-filled", "furnace", 150, "A [Fuel] [charcoal] chest gets charcoal sorted into it like any [charcoal] chest.")
def _(c):
    c.chest("copper", 2, 10, [("charcoal", 16)], block="waxed_copper_chest")
    c.chest("fuelchest", 12, 10)
    c.sign(12, 10, ["[Fuel]", "[charcoal]"])
    c.golem(4, 10)
    return lambda: [expect(c, "fuelchest", "charcoal", 16)]


@scenario("fuel-lava-bucket", "furnace", 150, "Only a lava bucket in the [Fuel] chest (furnace-fuel's default includes it): golems put it in the furnace's fuel slot.")
def _(c):
    fuel_setup(c, [("lava_bucket", 1)])
    return lambda: [expect(c, "fuelchest", "lava_bucket", 0),
                    (slot(c, "furnace", 1) in ("lava_bucket 1", "bucket 1") or c.total("bucket") > 0,
                     f"fuel slot: '{slot(c, 'furnace', 1)}' (want the lava bucket, or the empty bucket it left)")]


@scenario("fuel-bucket-refilled-at-cauldron", "furnace", 150, "An empty bucket left in a furnace's fuel slot and a lava cauldron nearby: a golem takes it out, fills it there and puts it in the [Fuel] chest.")
def _(c):
    furnace(c, "furnace", 8, 10, fuel=("bucket", 1), label="[raw iron]")
    c.chest("fuelchest", 14, 10)
    c.sign(14, 10, "[Fuel]")
    c.block(12, 4, "lava_cauldron")
    x, y, z = c.abs(12, 4)

    def check():
        emptied = "passed" in c.r.cmd(f"execute if block {x} {y} {z} minecraft:cauldron").lower()
        return [(emptied, "the lava cauldron was emptied"), expect(c, "fuelchest", "lava_bucket", 1),
                (slot(c, "furnace", 1) == "", f"fuel slot: '{slot(c, 'furnace', 1)}' (want empty)")]
    c.golem(4, 10)
    return check


@scenario("fuel-bucket-sorted-without-cauldron", "furnace", 150, "An empty bucket left in a furnace's fuel slot and no lava cauldron: a golem takes it out and sorts it into the bucket chest.")
def _(c):
    furnace(c, "furnace", 8, 10, fuel=("bucket", 1), label="[raw iron]")
    c.chest("buckets", 14, 10, [("bucket", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "buckets", "bucket", 2), (slot(c, "furnace", 1) == "", f"fuel slot: '{slot(c, 'furnace', 1)}' (want empty)")]


@scenario("check-furnace-report", "furnace", 30, "/cgc check on a furnace: what golems put in, a label entry that adds nothing, the fuel order and which [Fuel] chest would be used.")
def _(c):
    furnace(c, "furnace", 8, 10, block="blast_furnace", input=("raw_iron", 8), label=["[raw iron]", "[bread]"])
    c.chest("fuelchest", 14, 10, [("coal", 5)])
    c.sign(14, 10, "[Fuel]")
    furnace(c, "plain", 4, 14)
    x, y, z = c.abs(8, 10)
    px, py, pz = c.abs(4, 14)

    def check():
        reply = re.sub(r"§.", "", c.r.cmd(f"cgc check {x} {y} {z}"))
        plain = re.sub(r"§.", "", c.r.cmd(f"cgc check {px} {py} {pz}"))
        def has(r, t):
            return t in r, f"says '{t}'" + ("" if t in r else f"; got: {r.strip()[:400]}")
        return [has(reply, "Golems put into it (1 item): raw iron."),
                has(reply, "[bread]: nothing it covers can be cooked in a blast furnace"),
                has(reply, "first available of: charcoal, coal, lava bucket"),
                has(reply, "Items to cook come only from [Furnace Supply] containers."),
                has(reply, "Golems would use coal from the container at"),
                has(reply, "8 raw iron waiting, fuel: none; golems will bring 1 more"),
                has(plain, "No label: golems leave this furnace alone")]
    return check


# ---- claims

def set_home(c, place):
    """Give the platform's golems a home copper chest at this spot (another claim, or this one).
    The chest has to be there: a golem whose copper chest is gone forgets its home."""
    x, y, z = place[0], 150, place[1]
    if not (c.x <= x <= c.x + 20 and c.z <= z <= c.z + 20):
        c.r.cmd(f"forceload add {x} {z}")
        c.run(f"setblock {x} {y} {z} minecraft:waxed_copper_chest")
        c.cleanup += [f"setblock {x} {y} {z} minecraft:air", f"forceload remove {x} {z}"]
    c.run(f'execute as {c.selector("copper_golem")} run data merge entity @s '
          f'{{BukkitValues:{{"coppergolemclerk:home":"{WORLD};{x};{y};{z}"}}}}')


@scenario("furnace-claims-home-elsewhere", "furnace", 60, "Golem whose copper chest is in claim B, working on claim A (owner B has no container trust there): it never fetches from claim A's [Furnace Supply].", alone=True, place=PLACE_A)
def _(c):
    need_fixture()
    supply(c)
    furnace(c, "blast", 8, 10, block="blast_furnace", fuel=("coal", 16), label="[raw iron]")
    c.golem(4, 10)
    set_home(c, (PLACE_B[0] - 5, PLACE_B[1] - 5))
    return lambda: [(c.elapsed() > 40, "waited 40s"), expect(c, "supply", "raw_iron", 16)]


@scenario("furnace-claims-home-here", "furnace", 150, "The same, with the golem's copper chest in claim A: it fetches the supply's raw iron for the furnace.", alone=True, place=PLACE_A)
def _(c):
    need_fixture()
    supply(c)
    furnace(c, "blast", 8, 10, block="blast_furnace", fuel=("coal", 16), label="[raw iron]")
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    c.golem(4, 10)
    set_home(c, (PLACE_A[0] + 2, PLACE_A[1] + 10))
    return lambda: [expect(c, "supply", "raw_iron", 0)]
