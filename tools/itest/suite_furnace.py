"""furnace: golems feed, empty and fuel labelled furnaces (furnace-feeding).

Furnace slots: container.0 = input, container.1 = fuel, container.2 = result.
"""
from arena import Trigger, expect, scenario
from suite_options import set_options


def furnace(c, name, dx, dz, block="furnace", input=None, fuel=None, result=None, label=None):
    x, y, z = c.abs(dx, dz)
    c.run(f"setblock {x} {y} {z} minecraft:{block}[facing=south]")
    for slot, stack in ((0, input), (1, fuel), (2, result)):
        if stack:
            c.run(f"item replace block {x} {y} {z} container.{slot} with minecraft:{stack[0]} {stack[1]}")
    c.chests.setdefault(name, []).append((x, y, z))
    if label:
        c.sign(dx, dz, label)


def slot(c, name, n):
    """'item count' in a furnace slot, or '' if empty."""
    x, y, z = c.chests[name][0]
    out = c.r.cmd(f"data get block {x} {y} {z} Items")
    import re
    m = re.search(r'\{Slot: ' + str(n) + r'b, id: "minecraft:([a-z_]+)", count: (\d+)', out)
    return f"{m.group(1)} {m.group(2)}" if m else ""


@scenario("furnace-feed", "furnace", 150, "A golem with raw iron takes it to the blast furnace labelled [raw iron] rather than the raw iron chest.")
def _(c):
    c.chest("copper", 2, 10, [("raw_iron", 16)], block="waxed_copper_chest")
    furnace(c, "blast", 10, 10, block="blast_furnace", fuel=("coal", 16), label="[raw iron]")
    c.chest("raw", 14, 10, [("raw_iron", 1)])
    c.golem(4, 10)
    # Ingots may already have been taken out again by the idle golem: count them wherever they are.
    return lambda: [(c.count("blast", "raw_iron") + c.total("iron_ingot") == 16,
                     f"{c.count('blast', 'raw_iron')} raw iron in the furnace + {c.total('iron_ingot')} ingots (want 16 in all)"),
                    expect(c, "raw", "raw_iron", 1), expect(c, "copper", "raw_iron", 0)]


@scenario("furnace-empty", "furnace", 150, "An idle golem takes the iron ingots out of a labelled furnace and sorts them into the ingot chest.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    furnace(c, "furnace", 8, 10, result=("iron_ingot", 10), label="[raw iron]")
    c.chest("ingots", 14, 10, [("iron_ingot", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "ingots", "iron_ingot", 11), expect(c, "furnace", "iron_ingot", 0)]


@scenario("furnace-fuel", "furnace", 150, "A labelled furnace with raw iron and no fuel: an idle golem brings coal from the [Fuel] chest.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    furnace(c, "furnace", 8, 10, input=("raw_iron", 8), label="[raw iron]")
    c.chest("coal", 14, 10, [("coal", 32)])
    c.sign(14, 10, "[Fuel]")
    c.golem(4, 10)
    return lambda: [(c.count("coal", "coal") < 32, f"coal chest has {c.count('coal', 'coal')} (want fewer than 32)"),
                    (c.count("furnace", "coal") > 0 or c.count("furnace", "iron_ingot") > 0,
                     f"furnace fuel slot: '{slot(c, 'furnace', 1)}', result: '{slot(c, 'furnace', 2)}'")]


@scenario("furnace-unlabelled", "furnace", 150, "An unlabelled furnace is left alone: the raw iron goes to the raw iron chest.")
def _(c):
    c.chest("copper", 2, 10, [("raw_iron", 16)], block="waxed_copper_chest")
    furnace(c, "furnace", 8, 10, fuel=("coal", 16))
    c.chest("raw", 14, 10, [("raw_iron", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "raw", "raw_iron", 17), expect(c, "furnace", "raw_iron", 0)]


@scenario("furnace-cant-cook", "furnace", 150, "A smoker labelled [raw iron] can't cook it: the raw iron goes to the chest instead.")
def _(c):
    c.chest("copper", 2, 10, [("raw_iron", 16)], block="waxed_copper_chest")
    furnace(c, "smoker", 8, 10, block="smoker", fuel=("coal", 16), label="[raw iron]")
    c.chest("raw", 14, 10, [("raw_iron", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "raw", "raw_iron", 17), expect(c, "smoker", "raw_iron", 0)]


@scenario("furnace-no-loop", "furnace", 150, "Stone taken out of a furnace labelled [cobblestone] [stone] goes to the stone chest, not back into the furnace.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    furnace(c, "furnace", 8, 10, result=("stone", 8), label=["[cobblestone]", "[stone]"])
    c.chest("stone", 14, 10, [("stone", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "stone", "stone", 9), (slot(c, "furnace", 0) == "", f"furnace input: '{slot(c, 'furnace', 0)}' (want empty)")]


@scenario("option-furnace-feeding-off", "furnace", 150, "furnace-feeding: false: the labelled furnace is ignored; the raw iron goes to the chest.", alone=True)
def _(c):
    set_options(c, furnace_feeding="false")
    c.chest("copper", 2, 10, [("raw_iron", 16)], block="waxed_copper_chest")
    furnace(c, "furnace", 8, 10, fuel=("coal", 16), label="[raw iron]")
    c.chest("raw", 14, 10, [("raw_iron", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "raw", "raw_iron", 17), expect(c, "furnace", "raw_iron", 0)]


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


@scenario("check-furnace-report", "furnace", 30, "/cgc check on a furnace: what golems put in, a label entry that adds nothing, the fuel order and which [Fuel] chest would be used.")
def _(c):
    furnace(c, "furnace", 8, 10, block="blast_furnace", input=("raw_iron", 8), label=["[raw iron]", "[bread]"])
    c.chest("fuelchest", 14, 10, [("coal", 5)])
    c.sign(14, 10, "[Fuel]")
    furnace(c, "plain", 4, 14)
    x, y, z = c.abs(8, 10)
    px, py, pz = c.abs(4, 14)
    import re as _re

    def check():
        reply = _re.sub(r"§.", "", c.r.cmd(f"cgc check {x} {y} {z}"))
        plain = _re.sub(r"§.", "", c.r.cmd(f"cgc check {px} {py} {pz}"))
        def has(r, t):
            return t in r, f"says '{t}'" + ("" if t in r else f"; got: {r.strip()[:400]}")
        return [has(reply, "Golems put into it (1 item): raw iron."),
                has(reply, "[bread]: nothing it covers can be cooked in a blast furnace"),
                has(reply, "first available of: charcoal, coal"),
                has(reply, "Golems would use coal from the container at"),
                has(reply, "8 raw iron waiting, fuel: none; golems will bring 1 more"),
                has(plain, "No label: golems leave this furnace alone")]
    return check


@scenario("fuel-lava-bucket-refused", "furnace", 30, "furnace-fuel with lava_bucket: a warning, and it's skipped.", alone=True)
def _(c):
    set_options(c, furnace_fuel="[charcoal, lava_bucket]")
    return lambda: [(any("lava_bucket leaves an empty bucket" in l for l in c.log_lines()), "warned about lava_bucket")]
