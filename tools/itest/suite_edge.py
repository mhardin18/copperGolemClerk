"""edge: unusual labels, items, containers and vanilla mechanics around the golems."""
import re

from arena import Trigger, conserved, expect, scenario

SIGN = 'setblock {x} {y} {z} minecraft:oak_wall_sign[facing=north]{{{side}:{{messages:[{msgs}]}}}}'


def raw_sign(c, dx, dz, messages, side="front_text", dy=0):
    """Wall sign with exact SNBT messages (lets tests use colors, back sides, odd text)."""
    x, y, z = c.abs(dx, dz - 1, dy)
    c.run(SIGN.format(x=x, y=y, z=z, side=side, msgs=",".join(messages)))


def standing_sign_on_top(c, dx, dz, text):
    x, y, z = c.abs(dx, dz, 1)
    c.run(f'setblock {x} {y} {z} minecraft:oak_sign[rotation=8]{{front_text:{{messages:["{text}","","",""]}}}}')


def summon_frame(c, dx, dz, item, kind="item_frame", on_top=False):
    if on_top:
        x, y, z = c.abs(dx, dz, 1)
        facing = 1  # up
    else:
        x, y, z = c.abs(dx - 1, dz)
        facing = 4  # west
    item_nbt = f',Item:{{id:"minecraft:{item}",count:1}}' if item else ""
    c.run(f"summon minecraft:{kind} {x} {y} {z} {{Facing:{facing}b{item_nbt}}}")


def fetch_log(c):
    """(seconds, kind, item, chest) for each fetch start/take in this cell, from the debug log."""
    out = []
    for l in c.log_lines():
        m = re.search(r"^\[(\d+):(\d+):(\d+)\].*?(is idle; fetching|took \d+) (\w+) from overflow chest at ([-\d,]+)", l)
        if m and c.in_cell(l):
            t = int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3))
            out.append((t, "start" if "fetching" in m.group(4) else "take", m.group(5), m.group(6)))
    return out


# ---------------------------------------------------------------- containers

@scenario("locked-chest", "edge", 40, "The only log chest is locked (vanilla won't use it); logs go to [Overflow] promptly, not after the 30s fallback.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    x, y, z = c.abs(14, 10)
    c.run(f'setblock {x} {y} {z} minecraft:chest[facing=south]{{lock:{{items:"minecraft:diamond"}}}}')
    c.run(f"item replace block {x} {y} {z} container.0 with minecraft:oak_log 1")
    c.chests["logs"] = [(x, y, z)]
    c.chest("overflow", 8, 4)
    c.sign(8, 4, "[Overflow]")
    c.golem(4, 10)
    return lambda: [expect(c, "overflow", "birch_log", 16), expect(c, "logs", "birch_log", 0)]


@scenario("copper-chest-not-a-destination", "edge", 180, "A second copper chest holding birch logs is never a destination; all logs end in the log chest.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("copper2", 2, 16, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 16, 10, [("oak_log", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "logs", "birch_log", 32), conserved(c, "birch_log", 32)]


@scenario("hopper-under-destination", "edge", 150, "A hopper drains the log chest into a chest below; every birch log is accounted for, none duplicated.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 14, 10, [("oak_log", 1)])
    c.chest("hopper", 14, 10, block="hopper", dy=-1, facing="down")
    c.chest("below", 14, 10, dy=-2)
    c.golem(4, 10)
    return lambda: [(c.count("below", "birch_log") >= 1, f"{c.count('below', 'birch_log')} birch logs reached the chest below"),
                    conserved(c, "birch_log", 16)]


@scenario("shulker-and-bundle-contents", "edge", 180, "A shulker box with 5 diamonds and a bundle with 3 emeralds go to [storage] with their contents intact.")
def _(c):
    shulker = 'white_shulker_box[minecraft:container=[{slot:0,item:{id:"minecraft:diamond",count:5}}]]'
    bundle = 'bundle[minecraft:bundle_contents=[{id:"minecraft:emerald",count:3}]]'
    c.chest("copper", 2, 10, [(shulker, 1), (bundle, 1)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("storage", 14, 10)
    c.sign(14, 10, "[storage]")
    c.golem(4, 10)

    def inner(path):
        x, y, z = c.chests["storage"][0]
        m = re.search(r"data: (\d+)", c.r.cmd(f"data get block {x} {y} {z} {path}"))
        return int(m.group(1)) if m else 0

    def check():
        diamonds = inner('Items[{id:"minecraft:white_shulker_box"}].components."minecraft:container"[0].item.count')
        emeralds = inner('Items[{id:"minecraft:bundle"}].components."minecraft:bundle_contents"[0].count')
        return [expect(c, "storage", "white_shulker_box", 1), expect(c, "storage", "bundle", 1),
                (diamonds == 5, f"shulker box holds {diamonds} diamonds (want 5)"),
                (emeralds == 3, f"bundle holds {emeralds} emeralds (want 3)"), expect(c, "empty", "*", 0)]
    return check


# ---------------------------------------------------------------- items

@scenario("creative-only-items", "edge", 150, "Items in no group (bedrock, a spawn egg) go to [Overflow] rather than the nearer empty chest.")
def _(c):
    c.chest("copper", 2, 10, [("bedrock", 4), ("cow_spawn_egg", 2)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("overflow", 14, 10)
    c.sign(14, 10, "[Overflow]")
    c.golem(4, 10)
    return lambda: [expect(c, "overflow", "bedrock", 4), expect(c, "overflow", "cow_spawn_egg", 2), expect(c, "empty", "*", 0)]


@scenario("renamed-stack-no-merge", "edge", 150, "The log chest's only free space is a stack of renamed birch logs; plain birch can't merge into it, so it goes to [Overflow].")
def _(c):
    renamed = 'birch_log[minecraft:custom_name="Special"]'
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 14, 10, [("oak_log", 64)] * 26 + [(renamed, 32)])
    c.chest("overflow", 8, 4)
    c.sign(8, 4, "[Overflow]")
    c.golem(4, 10)
    return lambda: [expect(c, "overflow", "birch_log", 16), expect(c, "logs", "birch_log", 32), conserved(c, "birch_log", 48)]


# ---------------------------------------------------------------- labels

@scenario("sign-back-side", "edge", 120, "A sign with [logs] written only on its back side still labels the chest.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("logs", 14, 10)
    raw_sign(c, 14, 10, ['""', '""', '""', '""'])
    x, y, z = c.abs(14, 9)
    c.run(f'data merge block {x} {y} {z} {{back_text:{{messages:["[logs]","","",""]}}}}')
    c.golem(4, 10)
    return lambda: [expect(c, "logs", "birch_log", 16), expect(c, "empty", "*", 0)]


@scenario("standing-sign-on-top", "edge", 120, "A standing sign on top of a chest labels it (and doesn't count as blocking the lid).")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("logs", 14, 10)
    standing_sign_on_top(c, 14, 10, "[logs]")
    c.golem(4, 10)
    return lambda: [expect(c, "logs", "birch_log", 16), expect(c, "empty", "*", 0)]


@scenario("styled-sign-text", "edge", 120, "Colored, bold sign text '[logs]' works like plain text.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("logs", 14, 10)
    raw_sign(c, 14, 10, ['{text:"[logs]",color:"red",bold:true}', '""', '""', '""'])
    c.golem(4, 10)
    return lambda: [expect(c, "logs", "birch_log", 16), expect(c, "empty", "*", 0)]


@scenario("multi-line-sign", "edge", 180, "A sign with [logs] and [stone] takes both birch logs and granite.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16), ("granite", 16)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("mixed", 14, 10)
    raw_sign(c, 14, 10, ['"[logs]"', '"[stone]"', '""', '""'])
    c.golem(4, 10)
    return lambda: [expect(c, "mixed", "birch_log", 16), expect(c, "mixed", "granite", 16), expect(c, "empty", "*", 0)]


@scenario("junk-sign-text", "edge", 120, "Signs that aren't labels ([no such thing], [[logs]], logs, [🍞]) are ignored; the chest acts on its contents.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("logs", 14, 10, [("oak_log", 1)])
    raw_sign(c, 14, 10, ['"[no such thing xyz]"', '"[[logs]]"', '"logs"', '"[🍞]"'])
    summon_frame(c, 14, 10, None)  # an empty frame is no label either
    c.golem(4, 10)
    return lambda: [expect(c, "logs", "birch_log", 16), expect(c, "empty", "*", 0)]


@scenario("label-plus-overflow", "edge", 180, "One chest signed [logs] and [Overflow]: birch logs match the label, a sculk shrieker uses it as overflow.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16), ("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("both", 14, 10)
    raw_sign(c, 14, 10, ['"[logs]"', '"[Overflow]"', '""', '""'])
    c.golem(4, 10)
    return lambda: [expect(c, "both", "birch_log", 16), expect(c, "both", "sculk_shrieker", 1), expect(c, "empty", "*", 0)]


@scenario("glow-frame-label", "edge", 120, "A glow item frame works as a label like a normal frame.")
def _(c):
    c.chest("copper", 2, 10, [("stone_pickaxe", 1)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("labelled", 14, 10)
    summon_frame(c, 14, 10, "stone_pickaxe", kind="glow_item_frame")
    c.golem(4, 10)
    return lambda: [expect(c, "labelled", "stone_pickaxe", 1), expect(c, "empty", "*", 0)]


@scenario("frame-on-top-beats-group", "edge", 150, "A frame on top showing birch logs (1 item) beats a nearer chest of oak logs (logs group) for birch.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("oak", 7, 10, [("oak_log", 1)])
    c.chest("birch", 16, 10)
    summon_frame(c, 16, 10, "birch_log", on_top=True)
    c.golem(4, 10)
    return lambda: [expect(c, "birch", "birch_log", 16), expect(c, "oak", "birch_log", 0)]


# ---------------------------------------------------------------- golems

@scenario("leashed-golem-ignored", "edge", 45, "A leashed idle golem is left alone: it doesn't fetch from overflow (vanilla doesn't sort with leashed golems).")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    c.chest("overflow", 8, 10, [("granite", 16)])
    c.sign(8, 10, "[Overflow]")
    c.chest("stone", 14, 10, [("andesite", 1)])
    c.block(4, 6, "oak_fence")
    x, y, z = c.abs(4, 7)
    fx, fy, fz = c.abs(4, 6)
    c.run(f'summon minecraft:copper_golem {x + 0.5} {y} {z + 0.5} {{leash:[I;{fx},{fy},{fz}],active_effects:[{{id:"minecraft:luck",duration:-1,show_particles:0b}}]}}')
    return lambda: [(c.elapsed() >= 35, f"watched {c.elapsed():.0f}s of 35s"),
                    expect(c, "overflow", "granite", 16), (c.holding() == 0, f"golem holding {c.holding()} (want 0)")]


@scenario("fetcher-limit", "edge", 300, "Six idle golems, six fetchable items in one overflow chest: never more than 2 fetch from it at once, all 6 delivered.")
def _(c):
    items = [("birch_log", "oak_log", "logs"), ("granite", "andesite", "stone"), ("orange_wool", "white_wool", "wool"),
             ("red_stained_glass", "glass", "glass"), ("blue_dye", "red_dye", "dyes"), ("iron_ore", "coal_ore", "ores")]
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    c.double_chest("overflow", 11, 2, [(fetch, 16) for fetch, _, _ in items])
    c.sign(11, 2, "[Overflow]")
    for n, (fetch, seed, name) in enumerate(items):
        c.chest(name, 6 + 2 * n, 18, [(seed, 1)])
    for n in range(6):
        c.golem(3 + n, 10)

    def max_concurrent():
        active, peak = set(), 0
        for t, kind, item, chest in fetch_log(c):
            if kind == "start":
                active.add(item)
            else:
                active.discard(item)
            peak = max(peak, len(active))
        return peak

    def check():
        peak = max_concurrent()
        return [(peak <= 2, f"at most {peak} golems fetched from the overflow chest at once (want 2 or fewer)")] + \
               [expect(c, name, fetch, 16) for fetch, _, name in items]
    return check


@scenario("homeless-item-skipped", "edge", 480, "After a no-home item is returned, a birch log added behind it in the copper chest is taken first.")
def _(c):
    c.chest("copper", 2, 10, [("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("logs", 16, 10, [("oak_log", 1)])
    c.golem(4, 10)
    picked, added = Trigger(), Trigger()
    x, y, z = c.chests["copper"][0]

    def returned():
        return picked.at is not None and c.count("copper", "sculk_shrieker") == 1 and c.holding() == 0

    def check():
        picked.fire_when(lambda: c.holding("sculk_shrieker") > 0, lambda: None)
        added.fire_when(returned, lambda: c.run(f"item replace block {x} {y} {z} container.5 with minecraft:birch_log 16"))
        if added.at is None:
            return [(False, "waiting for the shrieker to be returned")]
        return [expect(c, "logs", "birch_log", 16), expect(c, "copper", "sculk_shrieker", 1)]
    return check


@scenario("sign-patterns-tags-properties", "edge", 200, "Signs with [#planks], [*_ore] and [@edible] work like group signs.")
def _(c):
    c.chest("copper", 2, 10, [("spruce_planks", 16), ("iron_ore", 8), ("bread", 8)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("planks", 10, 16)
    c.sign(10, 16, "[#planks]")
    c.chest("ores", 14, 16)
    c.sign(14, 16, "[*_ore]")
    c.chest("food", 18, 16)
    c.sign(18, 16, "[@edible]")
    c.golem(4, 10)
    return lambda: [expect(c, "planks", "spruce_planks", 16), expect(c, "ores", "iron_ore", 8),
                    expect(c, "food", "bread", 8), expect(c, "empty", "*", 0)]


@scenario("sign-on-wrong-block", "edge", 120, "A wall sign next to a chest but hung on a different block isn't the chest's label.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("empty", 14, 10)
    c.chest("logs", 8, 16, [("oak_log", 1)])
    # Chest at (14,10); a stone block north of it at (14,8) carries a sign at (14,9) facing south,
    # so the sign touches the chest but belongs to the stone block.
    c.block(14, 8, "stone")
    x, y, z = c.abs(14, 9)
    c.run(f'setblock {x} {y} {z} minecraft:oak_wall_sign[facing=south]{{front_text:{{messages:["[logs]","","",""]}}}}')
    c.golem(4, 10)
    return lambda: [expect(c, "logs", "birch_log", 16), expect(c, "empty", "*", 0)]


@scenario("frame-on-wrong-block", "edge", 120, "An item frame on the block beside a chest (facing it) isn't the chest's label.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("empty", 14, 10)
    c.chest("logs", 8, 16, [("oak_log", 1)])
    # Stone at (12,10); a frame at (13,10) hangs on the stone facing east (toward the chest at 14,10).
    c.block(12, 10, "stone")
    x, y, z = c.abs(13, 10)
    c.run(f'summon minecraft:item_frame {x} {y} {z} {{Facing:5b,Item:{{id:"minecraft:birch_log",count:1}}}}')
    c.golem(4, 10)
    return lambda: [expect(c, "logs", "birch_log", 16), expect(c, "empty", "*", 0)]


@scenario("unreachable-home-no-ping-pong", "edge", 150, "Granite's only home is walled off: an idle golem fetches it from overflow at most twice in two minutes, not over and over.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    c.chest("overflow", 8, 10, [("granite", 16)])
    c.sign(8, 10, "[Overflow]")
    c.chest("stone", 16, 10, [("andesite", 1)])
    x, y, z = c.abs(16, 10)
    c.run(f"fill {x - 1} {y} {z - 1} {x + 1} {y + 1} {z + 1} minecraft:glass replace minecraft:air")  # glass box, chest inside
    c.golem(4, 10)

    def takes():
        return sum(1 for l in c.log_lines() if "took" in l and "GRANITE" in l and c.in_cell(l))

    def check():
        n = takes()
        return [(c.elapsed() >= 120, f"watched {c.elapsed():.0f}s of 120s"),
                (n <= 2, f"granite fetched {n} times (want 2 or fewer)"), conserved(c, "granite", 16)]
    return check


@scenario("home-just-out-of-range-up", "edge", 120, "The only log chest is 9 blocks up (vanilla's search reaches 8): it's ignored, the logs go to [Overflow].")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("high", 10, 16, [("oak_log", 1)], dy=9)
    c.chest("overflow", 14, 10)
    c.sign(14, 10, "[Overflow]")
    c.golem(4, 10)
    return lambda: [expect(c, "overflow", "birch_log", 16), expect(c, "high", "birch_log", 0)]


@scenario("home-out-of-range-side", "edge", 120, "The only log chest is beyond 32 blocks sideways (vanilla's search range): it's ignored, the logs go to [Overflow].")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    x, y, z = c.abs(54, 10)  # over 32 blocks from anywhere on the platform (x 0-20)
    c.run(f"setblock {x} {y - 1} {z} minecraft:glowstone")
    c.run(f"setblock {x} {y} {z} minecraft:chest[facing=south]")
    c.run(f"item replace block {x} {y} {z} container.0 with minecraft:oak_log 1")
    c.chests["far"] = [(x, y, z)]
    c.chest("overflow", 14, 10)
    c.sign(14, 10, "[Overflow]")
    c.golem(4, 10)
    return lambda: [expect(c, "overflow", "birch_log", 16), expect(c, "far", "birch_log", 0)]


@scenario("hopper-stream-into-copper-chest", "edge", 300, "A hopper feeds 64 birch logs into the copper chest bit by bit; the golem sorts the whole stream.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    c.chest("hopper", 2, 10, block="hopper", dy=1, facing="down")
    c.chest("feed", 2, 10, [("birch_log", 64)], dy=2)
    c.chest("logs", 14, 10, [("oak_log", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "logs", "birch_log", 64), (c.total("birch_log") == 64, f"{c.total('birch_log')} birch logs in total (want 64)")]


@scenario("frame-and-sign-together", "edge", 180, "A chest with a framed stone pickaxe and a [logs] sign takes both.")
def _(c):
    c.chest("copper", 2, 10, [("stone_pickaxe", 1), ("birch_log", 16)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("both", 14, 10)
    c.frame(14, 10, "stone_pickaxe")
    c.sign(14, 10, "[logs]")
    c.golem(4, 10)
    return lambda: [expect(c, "both", "stone_pickaxe", 1), expect(c, "both", "birch_log", 16), expect(c, "empty", "*", 0)]


@scenario("fetch-goes-most-specific", "edge", 180, "A stew fetched from [Overflow] goes to the [stew] chest, not the [food] chest.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    c.chest("overflow", 8, 10, [("mushroom_stew", 1)])
    c.sign(8, 10, "[Overflow]")
    c.chest("food", 12, 16)
    c.sign(12, 16, "[food]")
    c.chest("stew", 18, 16)
    c.sign(18, 16, "[stew]")
    c.golem(4, 10)
    return lambda: [expect(c, "stew", "mushroom_stew", 1), expect(c, "food", "mushroom_stew", 0)]


@scenario("label-beats-contents", "edge", 150, "A [logs] chest wins over a nearer unlabelled chest that already holds birch logs.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("has-birch", 7, 10, [("birch_log", 4)])
    c.chest("labelled", 16, 10)
    c.sign(16, 10, "[logs]")
    c.golem(4, 10)
    return lambda: [expect(c, "labelled", "birch_log", 16), expect(c, "has-birch", "birch_log", 4)]


@scenario("unreachable-best-falls-back", "edge", 200, "The most specific home (framed birch log) is walled off; the reachable [logs] chest gets the logs before [Overflow] is tried.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("framed", 16, 4)
    c.frame(16, 4, "birch_log")
    x, y, z = c.abs(16, 4)
    c.run(f"fill {x - 2} {y} {z - 1} {x + 1} {y + 1} {z + 1} minecraft:glass replace minecraft:air")  # wall it in, frame inside
    c.chest("logs", 16, 16)
    c.sign(16, 16, "[logs]")
    c.chest("overflow", 8, 16)
    c.sign(8, 16, "[Overflow]")
    c.golem(4, 10)
    return lambda: [expect(c, "logs", "birch_log", 16), expect(c, "overflow", "birch_log", 0)]


@scenario("hopper-input-not-a-destination", "edge", 240, "An input chest drains through a hopper into a sunken copper chest: golems never put items back into it, so nothing loops.")
def _(c):
    c.chest("copper", 5, 10, block="waxed_copper_chest", dy=-1)   # sunk into the floor, still reachable
    c.chest("input", 6, 10, [("birch_log", 16)])
    x, y, z = c.abs(6, 10, -1)
    c.run(f"setblock {x} {y} {z} minecraft:hopper[facing=west]")  # under the input, emptying into the copper chest
    c.chests["hopper"] = [(x, y, z)]
    c.chest("logs", 16, 10, [("oak_log", 1)])
    c.golem(3, 12)
    return lambda: [expect(c, "logs", "birch_log", 16), expect(c, "input", "birch_log", 0), conserved(c, "birch_log", 16)]


@scenario("label-overflow-no-loop", "edge", 90, "A chest signed [logs] and [Overflow] holding birch logs: idle golems don't fetch the logs out just to put them back.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    c.chest("both", 12, 10, [("birch_log", 16)])
    c.sign(12, 10, ["[logs]", "[Overflow]"])
    c.golem(4, 10)

    def fetches():
        return sum(1 for l in c.log_lines() if "fetching" in l and "BIRCH_LOG" in l and c.in_cell(l))

    def check():
        n = fetches()
        return [(c.elapsed() > 60, f"watched {c.elapsed():.0f}s of 60s"), (n == 0, f"{n} fetches of birch logs (want 0)"),
                expect(c, "both", "birch_log", 16)]
    return check
