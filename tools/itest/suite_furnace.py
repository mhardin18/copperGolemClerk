"""furnace: golems feed, empty and fuel labelled furnaces (furnace-feeding).

Furnace slots: container.0 = input, container.1 = fuel, container.2 = result.
"""
from arena import expect, scenario
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


@scenario("furnace-fuel", "furnace", 150, "A labelled furnace with raw iron and no fuel: an idle golem brings coal from the coal chest.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    furnace(c, "furnace", 8, 10, input=("raw_iron", 8), label="[raw iron]")
    c.chest("coal", 14, 10, [("coal", 32)])
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
