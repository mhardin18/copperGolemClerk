"""scenes: whole sorting rooms, end to end, with the real groups from config.yml."""
from arena import expect, scenario

# (item, group sign, expected chest name); one stack each in the copper chests.
ROOM = [
    ("birch_log", "[logs]", "logs"), ("granite", "[stone]", "stone"), ("iron_ore", "[ores]", "ores"),
    ("bread", "[food]", "food"), ("repeater", "[redstone]", "redstone"), ("iron_pickaxe", "[tools]", "tools"),
    ("white_wool", "[wool]", "wool"), ("glass", "[glass]", "glass"), ("red_dye", "[dyes]", "dyes"),
    ("poppy", "[flowers]", "flowers"), ("torch", "[light sources]", "light"), ("painting", "[decorations]", "decorations"),
]
NO_HOME = "sculk_shrieker"


def build_room(c, signs):
    stacks = [(item, 1 if item in ("iron_pickaxe",) else 8) for item, _, _ in ROOM] + [(NO_HOME, 1)]
    c.chest("copper", 2, 6, stacks[:7], block="waxed_copper_chest")
    c.chest("copper2", 2, 14, stacks[7:], block="waxed_copper_chest")
    # Two rows of destination chests along the far side; a gap between each so signs fit.
    spots = [(8 + 2 * (n % 6), 3 if n < 6 else 17) for n in range(len(ROOM))]
    for (item, sign, name), (dx, dz) in zip(ROOM, spots):
        if signs:
            c.chest(name, dx, dz)
            c.sign(dx, dz, sign)
        else:
            seed = {"birch_log": "oak_log", "granite": "andesite", "iron_ore": "coal_ore", "bread": "apple",
                    "repeater": "comparator", "iron_pickaxe": "wooden_axe", "white_wool": "black_wool",
                    "glass": "white_stained_glass", "red_dye": "blue_dye", "poppy": "dandelion",
                    "torch": "lantern", "painting": "item_frame"}[item]
            c.chest(name, dx, dz, [(seed, 1)])
    c.chest("overflow", 18, 10)
    c.sign(18, 10, "[Overflow]")
    for dz in (8, 10, 12):
        c.golem(5, dz)
    return lambda: [expect(c, name, item, 1 if item == "iron_pickaxe" else 8) for item, _, name in ROOM] + \
                   [expect(c, "overflow", NO_HOME, 1)]


@scenario("sorting-room-signs", "scenes", 420, "A 12-chest sorting room labelled with signs: every item reaches its group's chest, the unhomed one goes to [Overflow].")
def _(c):
    return build_room(c, signs=True)


@scenario("sorting-room-contents", "scenes", 420, "The same room with no signs, each chest seeded with one example item: everything still sorts by group.")
def _(c):
    return build_room(c, signs=False)


SOAK = [("birch_log", "[logs]", "logs"), ("granite", "[stone]", "stone"), ("bread", "[food]", "food"),
        ("iron_ore", "[ores]", "ores"), ("red_dye", "[dyes]", "dyes"), ("glass", "[glass]", "glass")]


@scenario("soak-hopper-stream", "scenes", 1500, "Soak: two hoppers trickle 1,728 mixed items into two copper chests; six golems sort them all (hundreds of small trips), ticks stay healthy, nothing is lost. ~10 minutes.",
          manual=True)
def _(c):
    from suite_perf import mspt
    per_item = 9 * 64  # 576 of each, 1,728 items in all; hoppers trickle them in, so trips are many and small
    feed = []
    for item, _, _ in SOAK:
        feed += [(item, 64)] * 9
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    c.chest("hopperA", 2, 10, block="hopper", dy=1, facing="down")
    c.chest("feedA", 2, 10, feed[:27], dy=2)
    c.chest("copperB", 2, 14, block="waxed_copper_chest")
    c.chest("hopperB", 2, 14, block="hopper", dy=1, facing="down")
    c.chest("feedB", 2, 14, feed[27:], dy=2)
    for n, (item, sign, name) in enumerate(SOAK):
        c.double_chest(name, 9 + 2 * (n % 3) * 2, 3 if n < 3 else 18)
        c.sign(9 + 2 * (n % 3) * 2, 3 if n < 3 else 18, sign)
    c.chest("overflow", 19, 10)
    c.sign(19, 10, "[Overflow]")
    for dz in (6, 8, 10, 12, 14, 16):
        c.golem(5, dz)
    samples = []

    def check():
        if int(c.elapsed()) % 60 < 3 and (not samples or c.elapsed() - samples[-1][0] > 30):
            value = mspt(c)
            if value is not None:
                samples.append((c.elapsed(), value))
        worst = max((v for _, v in samples), default=None)
        return [expect(c, name, item, per_item) for item, _, name in SOAK] + \
               [(worst is None or worst < 40, f"worst tick time {worst} ms over {len(samples)} samples (want under 40)"),
                (c.count("overflow", "*") == 0, f"overflow holds {c.count('overflow', '*')} items (want 0)")]
    return check
