"""tuning: manual layouts for measuring furnace feeding (run by tools/itest/tune.py, which sets
the settings for each batch). Each layout runs TUNE_SECONDS and prints one line
"TUNE {json}" with what it measured:

  cooked        items cooked in the run (outputs that appeared anywhere on the platform)
  per_min       cooked per minute
  trips         trips by kind (supply, fuel, out = carrying cooked items, other) and avg load
  idle_s        seconds each furnace had nothing to cook
  unlit_s       seconds each furnace wasn't burning
  heat_per_item fuel burnt (in items' worth: coal/charcoal 8, lava 100) per item cooked, 1.0 = no waste
  overflow_left cooked items still in [Overflow] chests at the end
  supply_left   items still to cook (supply chests, furnace inputs, golems' hands)
"""
import json
import os
import re
import threading
import time

import arena
from arena import scenario
from rcon import from_properties
from suite_furnace import furnace, hopper
from suite_lava import drip_cauldron

TUNE_SECONDS = int(os.environ.get("TUNE_SECONDS", "360"))  # tune.py sets it per batch ("seconds")

COOKS = {"raw_iron": "iron_ingot", "raw_gold": "gold_ingot", "raw_copper": "copper_ingot", "sand": "glass",
         "potato": "baked_potato", "beef": "cooked_beef", "cobblestone": "stone"}
FUEL_HEAT = {"charcoal": 8, "coal": 8, "lava_bucket": 100}


class Sampler(threading.Thread):
    """Once a second: each tagged golem's hand, each furnace's input and fire. Own RCON connection."""

    def __init__(self, c, tags, furnaces):
        super().__init__(daemon=True)
        self.c, self.tags, self.furnaces = c, tags, furnaces
        self.r = from_properties(arena.SERVER + "/server.properties")
        self.hands = {}
        self.trips = {}
        self.idle = {n: 0 for n in furnaces}
        self.unlit = {n: 0 for n in furnaces}
        self.wasted = {n: 0 for n in furnaces}  # burning with nothing to cook: fuel thrown away
        self.stop = False

    @staticmethod
    def kind(item):
        if item in COOKS:
            return "supply"
        if item in FUEL_HEAT or item == "bucket":
            return "fuel"
        if item in COOKS.values():
            return "out"
        return "other"

    def run(self):
        while not self.stop:
            try:
                for tag in self.tags:
                    out = self.r.cmd(f"data get entity @e[type=copper_golem,tag={tag},limit=1] equipment.mainhand")
                    m = re.search(r'id: "minecraft:(\w+)", count: (\d+)', out)
                    now = (m.group(1), int(m.group(2))) if m else None
                    if now and self.hands.get(tag) is None:
                        t = self.trips.setdefault(self.kind(now[0]), [0, 0])
                        t[0] += 1
                        t[1] += now[1]
                    self.hands[tag] = now
                for name, (x, y, z) in self.furnaces.items():
                    empty = "Slot: 0b" not in self.r.cmd(f"data get block {x} {y} {z} Items")
                    if empty:
                        self.idle[name] += 1
                    lit = re.search(r"data: (\d+)", self.r.cmd(f"data get block {x} {y} {z} lit_time_remaining"))
                    if not lit or lit.group(1) == "0":
                        self.unlit[name] += 1
                    elif empty:
                        self.wasted[name] += 1
            except Exception:
                pass
            time.sleep(1)


def golems(c, n, row=10):
    tags = []
    for i in range(n):
        tag = f"tune{c.z}g{i}"
        x, y, z = c.abs(2 + (i * 2) % 17, row + (i // 9) * 2)
        c.run(f'summon minecraft:copper_golem {x + 0.5} {y} {z + 0.5} {{Tags:["{tag}"],'
              f'active_effects:[{{id:"minecraft:luck",duration:-1,show_particles:0b,show_icon:0b}}]}}')
        tags.append(tag)
    return tags


def totals(c, items):
    return {i: c.total(i) for i in items}


def measure(c, name, tags, furnace_names, inputs, overflow=("overflow",)):
    """Start the sampler; the returned check prints the TUNE line when the time is up."""
    outputs = sorted({COOKS[i] for i in inputs})
    start_out = totals(c, outputs)
    start_fuel = totals(c, list(FUEL_HEAT))
    sampler = Sampler(c, tags, {n: c.chests[n][0] for n in furnace_names})
    sampler.start()
    done = []

    def check():
        if c.elapsed() > TUNE_SECONDS and not done:
            sampler.stop = True
            end_out = totals(c, outputs)
            end_fuel = totals(c, list(FUEL_HEAT))
            cooked = sum(end_out[o] - start_out[o] for o in outputs)
            heat = sum((start_fuel[f] - end_fuel[f]) * FUEL_HEAT[f] for f in FUEL_HEAT)
            # Fuel still burning at the end isn't used yet: take off each furnace's remaining burn time
            # (in items: a furnace cooks one per 200 ticks, a blast furnace or smoker one per 100).
            for fname in furnace_names:
                x, y, z = c.chests[fname][0]
                left = re.search(r"data: (\d+)", c.r.cmd(f"data get block {x} {y} {z} lit_time_remaining"))
                total = re.search(r"data: (\d+)", c.r.cmd(f"data get block {x} {y} {z} cooking_total_time"))
                if left and total and int(total.group(1)) > 0:
                    heat -= int(left.group(1)) / int(total.group(1))
            result = {
                "layout": name, "golems": len(tags), "furnaces": len(furnace_names), "cooked": cooked,
                "per_min": round(cooked / (TUNE_SECONDS / 60), 1),
                "trips": {k: {"n": v[0], "avg": round(v[1] / v[0], 1)} for k, v in sorted(sampler.trips.items())},
                "trips_per_item": round(sum(v[0] for v in sampler.trips.values()) / max(1, cooked), 2),
                "idle_s": sampler.idle, "unlit_s": sampler.unlit, "wasted_burn_s": sampler.wasted,
                "heat_per_item": round(heat / max(1, cooked), 2),
                "overflow_left": sum(c.count(o, it) for o in overflow if o in c.chests for it in outputs),
                "supply_left": sum(c.total(i) for i in inputs),
            }
            print("TUNE " + json.dumps(result), flush=True)
            done.append(True)
        return [(bool(done), f"measured for {TUNE_SECONDS}s")]
    return check


# ---- layouts

def survival_smelter(c, name, n, one_furnace=False, lava=False):
    """The survival smelter: 2 furnaces on hoppers into an [Overflow] double chest, a third without
    a hopper, [Furnace Supply] and [Fuel] double chests (see suite_furnace_trips.smelter)."""
    c.double_chest("supply", 3, 4, [("raw_copper", 64)] * 4 + [("raw_copper", 47), ("raw_gold", 3), ("raw_iron", 11)])
    c.sign(3, 4, "[Furnace Supply]")
    hopper(c, "hopperW", 4, 4, "east", dy=0)
    furnace(c, "furnaceW", 4, 4, dy=1, label="[*]")
    c.double_chest("overflow", 6, 4)
    c.sign(6, 4, "[Overflow]")
    names = ["furnaceW"]
    if not one_furnace:
        hopper(c, "hopperE", 7, 4, "west", dy=0)
        furnace(c, "furnaceE", 7, 4, dy=1, label="[*]")
        furnace(c, "furnace3", 10, 14, label="[*]")
        names += ["furnaceE", "furnace3"]
    fuel = [("charcoal", 64)] * 3 + [("charcoal", 35)]
    if lava:
        fuel = [("lava_bucket", 1)] * 6 + [("charcoal", 64)]
        drip_cauldron(c, 14, 4, full=True)
        c.chest("buckets", 18, 18, [("bucket", 1)])
        c.sign(18, 18, "[bucket]")
    c.double_chest("fuel", 9, 4, fuel)
    c.sign(9, 4, "[Fuel]")
    c.chest("copper", 10, 12, block="waxed_copper_chest")
    c.chest("ingots", 14, 14, [("copper_ingot", 1)])
    c.sign(14, 14, "[ingots and gems]")
    tags = golems(c, n)
    return measure(c, name, tags, names, ["raw_copper", "raw_gold", "raw_iron"])


def big_smelter(c, name, n):
    """Six labelled furnaces (2 furnaces, 2 blast furnaces, 2 smokers), no hoppers, a mixed supply
    (ores, sand, food), coal and charcoal; golems take the output out and sort it."""
    kinds = [("f1", "furnace"), ("f2", "furnace"), ("b1", "blast_furnace"), ("b2", "blast_furnace"), ("s1", "smoker"), ("s2", "smoker")]
    for i, (fname, block) in enumerate(kinds):
        furnace(c, fname, 3 + i * 2, 4, block=block, label="[*]")
    c.double_chest("supply", 17, 4, [("raw_iron", 64)] * 3 + [("raw_gold", 64)] + [("sand", 64)] * 2
                   + [("potato", 64)] + [("beef", 64)])
    c.sign(17, 4, "[Furnace Supply]")
    c.chest("fuel", 17, 8, [("coal", 64)] * 3 + [("charcoal", 64)])
    c.sign(17, 8, "[Fuel]")
    c.chest("ingots", 4, 18, [("iron_ingot", 1)])
    c.sign(4, 18, ["[iron ingot]", "[gold ingot]"])
    c.chest("glass", 9, 18, [("glass", 1)])
    c.sign(9, 18, "[glass]")
    c.chest("food", 14, 18, [("baked_potato", 1)])
    c.sign(14, 18, ["[baked potato]", "[cooked beef]"])
    c.chest("copper", 2, 12, block="waxed_copper_chest")
    tags = golems(c, n)
    return measure(c, name, tags, [k for k, _ in kinds], ["raw_iron", "raw_gold", "sand", "potato", "beef"], overflow=())


def lava_smelter(c, name, n):
    """Three furnaces, 320 raw iron, [Fuel] with 6 lava buckets and charcoal, a full dripstone cauldron
    (normal ticks: it refills about every 20 minutes), output sorted by golems."""
    for i, fname in enumerate(("f1", "f2", "f3")):
        furnace(c, fname, 4 + i * 3, 4, label="[*]")
    c.double_chest("supply", 16, 4, [("raw_iron", 64)] * 5)
    c.sign(16, 4, "[Furnace Supply]")
    c.chest("fuel", 16, 8, [("lava_bucket", 1)] * 6 + [("charcoal", 64)])
    c.sign(16, 8, "[Fuel]")
    drip_cauldron(c, 12, 12, full=True)
    c.chest("ingots", 6, 18, [("iron_ingot", 1)])
    c.sign(6, 18, "[iron ingot]")
    c.chest("buckets", 18, 18, [("bucket", 1)])
    c.sign(18, 18, "[bucket]")
    c.chest("copper", 2, 12, block="waxed_copper_chest")
    tags = golems(c, n, row=14)
    return measure(c, name, tags, ["f1", "f2", "f3"], ["raw_iron"], overflow=())


def small_lava(c, name, n):
    """Three furnaces and only 40 raw iron to cook, [Fuel] with 6 lava buckets and charcoal: a
    bucket (100 items) would mostly burn with nothing to cook."""
    for i, fname in enumerate(("f1", "f2", "f3")):
        furnace(c, fname, 4 + i * 3, 4, label="[*]")
    c.chest("supply", 16, 4, [("raw_iron", 40)])
    c.sign(16, 4, "[Furnace Supply]")
    c.chest("fuel", 16, 8, [("lava_bucket", 1)] * 6 + [("charcoal", 64)])
    c.sign(16, 8, "[Fuel]")
    c.chest("ingots", 6, 18, [("iron_ingot", 1)])
    c.sign(6, 18, "[iron ingot]")
    c.chest("buckets", 18, 18, [("bucket", 1)])
    c.sign(18, 18, "[bucket]")
    c.chest("copper", 2, 12, block="waxed_copper_chest")
    tags = golems(c, n, row=14)
    return measure(c, name, tags, ["f1", "f2", "f3"], ["raw_iron"], overflow=())


DESC = "Manual tuning layout (tools/itest/tune.py): measured for 6 minutes, prints a TUNE line."
for g in (1, 2, 3, 5, 7, 10):
    scenario(f"tune-big-{g}g", "tuning", TUNE_SECONDS + 120, DESC, manual=True)(lambda c, g=g: big_smelter(c, f"big-{g}g", g))
    scenario(f"tune-survival-{g}g", "tuning", TUNE_SECONDS + 120, DESC, manual=True)(lambda c, g=g: survival_smelter(c, f"survival-{g}g", g))
    scenario(f"tune-one-{g}g", "tuning", TUNE_SECONDS + 120, DESC, manual=True)(lambda c, g=g: survival_smelter(c, f"one-{g}g", g, one_furnace=True))
    scenario(f"tune-lava-{g}g", "tuning", TUNE_SECONDS + 120, DESC, manual=True)(lambda c, g=g: lava_smelter(c, f"lava-{g}g", g))
    scenario(f"tune-lavasmall-{g}g", "tuning", TUNE_SECONDS + 120, DESC, manual=True)(lambda c, g=g: small_lava(c, f"lavasmall-{g}g", g))
    scenario(f"tune-survlava-{g}g", "tuning", TUNE_SECONDS + 120, DESC, manual=True)(lambda c, g=g: survival_smelter(c, f"survlava-{g}g", g, lava=True))
