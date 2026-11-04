"""furnace-edge: furnace feeding when things change mid-job, odd builds, and load.

Every scenario also checks that no exception reached the server log and that no item was
lost or duplicated.
"""
import re

from arena import Trigger, conserved, expect, scenario
from suite_furnace import cooked, furnace, hopper, supply
from suite_options import set_options


def no_errors(c):
    bad = [l for l in c.log_lines() if re.search(r"Exception|\bERROR\b|Could not pass event", l)]
    return not bad, f"no exceptions in the log; got: {bad[:1]}"


def iron_conserved(c, want=16):
    """Raw iron anywhere (chests, furnaces, hoppers, hands, ground) plus the ingots made from it."""
    got = c.total("raw_iron") + c.total("iron_ingot")
    return got == want, f"{got} raw iron + ingots exist (want {want}: none lost or duplicated)"


def logged(c, text):
    """True if a log line about this platform (one with a coordinate on it) has this text.
    Not the whole log: scenarios run side by side, and another platform's golem logging
    "waits up to" must not set off this one's trigger."""
    return any(text in l for l in c.cell_lines())


def raw_chest(c, dx=4, dz=16):
    c.chest("raw", dx, dz)
    c.sign(dx, dz, "[raw iron]")


@scenario("furnace-edge-furnace-broken-mid-feed", "furnace-edge", 180, "The labelled furnace is broken while a golem carries the supply's raw iron to it: the golem puts it back in the supply (never sorted into the [raw iron] chest); none lost.")
def _(c):
    supply(c, dx=18, dz=18)
    furnace(c, "blast", 2, 2, block="blast_furnace", label="[raw iron]")
    raw_chest(c)
    c.golem(10, 10)
    x, y, z = c.abs(2, 2)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.holding("raw_iron") > 0, lambda: c.r.cmd(f"setblock {x} {y} {z} minecraft:air"))
        return [(t.at is not None, "broke the furnace while the golem carried raw iron"),
                expect(c, "supply", "raw_iron", 16), expect(c, "raw", "raw_iron", 0), (c.holding() == 0, f"golem holding {c.holding()} (want 0)"), no_errors(c)]
    return check


@scenario("furnace-edge-supply-emptied-before-arrival", "furnace-edge", 90, "The supply chest is emptied after a golem sets off for it: the golem gives up quietly, nothing is invented, the furnace stays empty.")
def _(c):
    supply(c)
    furnace(c, "blast", 8, 10, block="blast_furnace", fuel=("coal", 16), label="[raw iron]")
    c.chest("stash", 4, 16)
    c.golem(4, 4, slow=True)
    sx, sy, sz = c.chests["supply"][0]
    tx, ty, tz = c.chests["stash"][0]
    t = Trigger()

    def empty_supply():
        c.r.cmd(f"item replace block {tx} {ty} {tz} container.0 from block {sx} {sy} {sz} container.0")
        c.r.cmd(f"item replace block {sx} {sy} {sz} container.0 with minecraft:air")

    def check():
        t.fire_when(lambda: logged(c, "SUPPLY_TAKE"), empty_supply)
        return [(t.at is not None and t.since() > 20, "emptied the supply as the golem set off, then watched 20s"),
                expect(c, "stash", "raw_iron", 16), (cooked(c, "blast") == 0, f"furnace has {cooked(c, 'blast')} (want 0)"),
                iron_conserved(c), no_errors(c)]
    return check


@scenario("furnace-edge-golem-killed-carrying", "furnace-edge", 120, "A golem carrying the supply's raw iron is killed: the raw iron drops (nothing vanishes).")
def _(c):
    supply(c, dx=18, dz=18)
    furnace(c, "blast", 2, 2, block="blast_furnace", label="[raw iron]")
    c.golem(10, 10)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.holding("raw_iron") > 0, lambda: c.r.cmd(f"kill {c.selector('copper_golem')}"))
        return [(t.at is not None and t.since() > 3, "killed the golem while it carried raw iron"),
                (c.dropped("raw_iron") + c.count("supply", "raw_iron") + c.count("blast", "raw_iron") == 16,
                 f"dropped {c.dropped('raw_iron')} + supply {c.count('supply', 'raw_iron')} + furnace {c.count('blast', 'raw_iron')} (want 16)"),
                no_errors(c)]
    return check


@scenario("furnace-edge-feeding-off-mid-job", "furnace-edge", 180, "furnace-feeding is turned off (reload) while a golem carries raw iron to a furnace: it stops and sorts the raw iron into the [raw iron] chest.", alone=True)
def _(c):
    supply(c, dx=18, dz=18)
    furnace(c, "blast", 2, 2, block="blast_furnace", label="[raw iron]")
    raw_chest(c)
    c.golem(10, 10)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.holding("raw_iron") > 0 and cooked(c, "blast") == 0, lambda: set_options(c, furnace_feeding="false"))
        return [(t.at is not None, "turned furnace feeding off mid-carry"), expect(c, "raw", "raw_iron", 16),
                (c.holding() == 0, f"golem holding {c.holding()} (want 0)"), no_errors(c)]
    return check


@scenario("furnace-edge-vanilla-mode-mid-job", "furnace-edge", 180, "A golem carrying raw iron to a furnace is switched to vanilla: the plugin lets go of it (no errors) and the raw iron isn't lost.")
def _(c):
    supply(c, dx=18, dz=18)
    furnace(c, "blast", 2, 2, block="blast_furnace", label="[raw iron]")
    c.chest("empty", 4, 16)
    c.golem(10, 10)
    t = Trigger()

    def to_vanilla():
        c.r.cmd(f'execute as {c.selector("copper_golem")} run data merge entity @s {{BukkitValues:{{"coppergolemclerk:mode":"vanilla"}}}}')

    def check():
        t.fire_when(lambda: c.holding("raw_iron") > 0 and cooked(c, "blast") == 0, to_vanilla)
        return [(t.at is not None and t.since() > 30, "switched to vanilla mid-carry, watched 30s"),
                iron_conserved(c), no_errors(c)]
    return check


@scenario("furnace-edge-cauldron-emptied-before-fill", "furnace-edge", 150, "The lava cauldron is emptied while a golem walks over with an empty bucket: the bucket is sorted into the bucket chest instead.")
def _(c):
    furnace(c, "furnace", 8, 10, fuel=("bucket", 1), label="[raw iron]")
    c.chest("buckets", 4, 16, [("bucket", 1)])
    c.block(16, 4, "lava_cauldron")
    x, y, z = c.abs(16, 4)
    c.golem(4, 10, slow=True)
    t = Trigger()

    def check():
        t.fire_when(lambda: any("(BUCKET_FILL)" in l for l in c.cell_lines()),
                    lambda: c.r.cmd(f"setblock {x} {y} {z} minecraft:cauldron"))
        return [(t.at is not None, "emptied the cauldron as the golem set off for it"),
                expect(c, "buckets", "bucket", 2), (c.total("lava_bucket") == 0, "no lava bucket was made"), no_errors(c)]
    return check


@scenario("furnace-edge-full-furnace", "furnace-edge", 60, "A labelled furnace whose input already holds 64 raw iron: golems leave the supply alone.")
def _(c):
    supply(c)
    furnace(c, "blast", 8, 10, block="blast_furnace", input=("raw_iron", 64), label="[raw iron]")
    c.golem(4, 10)
    return lambda: [(c.elapsed() > 40, "waited 40s"), expect(c, "supply", "raw_iron", 16), no_errors(c)]


@scenario("furnace-edge-renamed-raw-iron", "furnace-edge", 180, "The supply holds 8 plain and 8 renamed raw iron (they don't stack): golems feed both kinds over time and none is lost or duplicated.")
def _(c):
    c.chest("supply", 16, 10, [("raw_iron", 8), ('raw_iron[minecraft:custom_name="Lumpy"]', 8)])
    c.sign(16, 10, "[Furnace Supply]")
    furnace(c, "blast", 8, 10, block="blast_furnace", fuel=("coal", 16), label="[raw iron]")
    c.chest("ingots", 4, 16, [("iron_ingot", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "supply", "raw_iron", 0), iron_conserved(c, 17), no_errors(c)]


@scenario("furnace-edge-carry-64-three-furnaces", "furnace-edge", 180, "carry-amount: 64, 64 raw iron in the supply and three labelled blast furnaces: the load goes to 2 of them (never more than 2); none lost.", alone=True)
def _(c):
    set_options(c, carry_amount="64")
    supply(c, items=[("raw_iron", 64)])
    for i, dz in enumerate((4, 10, 16)):
        furnace(c, f"b{i}", 8, dz, block="blast_furnace", fuel=("coal", 16), label="[raw iron]")
    seen = set()

    def check():
        for i in range(3):
            if cooked(c, f"b{i}") > 0:
                seen.add(i)
        return [(len(seen) == 2, f"furnaces that got raw iron: {sorted(seen)} (want 2: a load goes to at most 2)"),
                expect(c, "supply", "raw_iron", 0), iron_conserved(c, 64), no_errors(c)]
    c.golem(4, 10)
    return check


@scenario("furnace-edge-two-golems-one-supply", "furnace-edge", 180, "Two golems, one supply of 32 raw iron and two labelled furnaces: everything goes in, none lost or duplicated.")
def _(c):
    supply(c, items=[("raw_iron", 16), ("raw_iron", 16)])
    furnace(c, "b1", 8, 6, block="blast_furnace", fuel=("coal", 16), label="[raw iron]")
    furnace(c, "b2", 8, 14, block="blast_furnace", fuel=("coal", 16), label="[raw iron]")
    c.chest("ingots", 4, 18, [("iron_ingot", 1)])
    c.golem(4, 8)
    c.golem(4, 12)
    return lambda: [expect(c, "supply", "raw_iron", 0), iron_conserved(c, 33), no_errors(c)]


@scenario("furnace-edge-output-no-home", "furnace-edge", 60, "A labelled furnace holding cracked stone bricks that no chest in range would take (and no copper chest to return them to): golems leave them in the furnace instead of carrying them forever.")
def _(c):
    furnace(c, "furnace", 8, 10, result=("cracked_stone_bricks", 10), label="[stone bricks]")
    c.chest("logs", 14, 10, [("oak_log", 1)])
    c.sign(14, 10, "[logs]")
    c.golem(4, 10)
    return lambda: [(c.elapsed() > 40, "waited 40s"), expect(c, "furnace", "cracked_stone_bricks", 10),
                    (c.holding() == 0, f"golem holding {c.holding()} (want 0)"), no_errors(c)]


@scenario("furnace-edge-chest-on-hopper-loop", "furnace-edge", 150, "A chest on four hoppers pushing round in a circle (no furnace): working out whether it feeds a smelter doesn't hang, and it's an ordinary destination.")
def _(c):
    c.chest("copper", 2, 10, [("dirt", 16)], block="waxed_copper_chest")
    hopper(c, "a", 8, 10, "east", dy=0)
    hopper(c, "b", 9, 10, "south", dy=0)
    hopper(c, "c", 9, 11, "west", dy=0)
    hopper(c, "d", 8, 11, "north", dy=0)
    c.chest("top", 8, 10, dy=1)
    c.golem(4, 10)
    loop = ("a", "b", "c", "d", "top")
    return lambda: [(sum(c.count(n, "dirt") for n in loop) == 16, f"{sum(c.count(n, 'dirt') for n in loop)} dirt in the chest and loop (want 16)"),
                    no_errors(c)]


@scenario("furnace-edge-many-furnaces", "furnace-edge", 90, "24 labelled furnaces, a supply of 27 kinds none of them can cook, and 4 idle golems: nothing is fetched, the server keeps up, no errors.")
def _(c):
    nothing = ["dirt", "oak_door", "glass", "oak_planks", "white_wool", "bricks", "obsidian", "torch", "ladder",
               "chest", "furnace", "hopper", "rail", "lever", "string", "feather", "bone", "arrow", "bow", "book",
               "paper", "map", "clock", "compass", "bucket", "bowl", "shears"]
    c.chest("supply", 18, 10, [(n, 1) for n in nothing])
    c.sign(18, 10, "[Furnace Supply]")
    for i in range(24):
        dx, dz = 2 + (i % 6) * 2, 4 + (i // 6) * 3
        furnace(c, f"f{i}", dx, dz, fuel=("coal", 1), label="[raw iron]")
    for dz in (2, 8, 14, 19):
        c.golem(16, dz)

    def check():
        lag = [l for l in c.log_lines() if "Can't keep up" in l]
        return [(c.elapsed() > 60, "watched 60s"), expect(c, "supply", "*", 27), (not lag, f"server kept up; got: {lag[:1]}"),
                no_errors(c)]
    return check
