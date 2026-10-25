"""tidy: golems tidy the chest they put something in (tidy-chests).

Layout: copper chest at (2,10), golem at (4,10), the chest under test further along +x.
"""
import re

from arena import Trigger, conserved, expect, scenario
from suite_options import set_options


def slots(c, name, block=0):
    """[(slot, id, count, named)] of one block of a named chest (the first by default), in list order."""
    x, y, z = c.chests[name][block]
    result = []
    for i in range(54):
        base = f"data get block {x} {y} {z} Items[{i}]"
        slot = re.search(r"data: (\d+)", c.r.cmd(base + ".Slot"))
        if not slot:
            break
        item = re.search(r'data: "minecraft:([^"]+)"', c.r.cmd(base + ".id")).group(1)
        count = int(re.search(r"data: (\d+)", c.r.cmd(base + ".count")).group(1))
        named = "data:" in c.r.cmd(base + '.components."minecraft:custom_name"')
        result.append((int(slot.group(1)), item, count, named))
    return result


def show(contents):
    return ", ".join(f"{s}:{'named ' if n else ''}{i} x{k}" for s, i, k, n in contents) or "empty"


def packed(contents):
    """Stacks fill slots 0..n-1 with no gaps."""
    return [s for s, *_ in contents] == list(range(len(contents)))


def together(contents, items):
    """The stacks of these items sit in one unbroken run of slots."""
    at = [pos for pos, (_, i, _, _) in enumerate(contents) if i in items]
    return bool(at) and at == list(range(at[0], at[0] + len(at)))


@scenario("tidy-merge-stacks", "tidy", 150, "A golem delivers birch logs to a log chest with split stacks: the stacks are merged, logs packed into the first slots.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 12, 10, [("oak_log", 10), ("air", 1), ("spruce_log", 5), ("air", 1), ("oak_log", 20),
                             ("air", 1), ("birch_log", 3), ("air", 1), ("spruce_log", 4)])
    c.sign(12, 10, "[logs]")
    c.golem(4, 10)

    def check():
        got = slots(c, "logs")
        by_item = {}
        for _, item, count, _ in got:
            by_item.setdefault(item, []).append(count)
        return [(by_item.get("birch_log") == [19], f"birch logs in one stack of 19 ({show(got)})"),
                (by_item.get("oak_log") == [30], "oak logs merged into one stack of 30"),
                (by_item.get("spruce_log") == [9], "spruce logs merged into one stack of 9"),
                (packed(got), "no gaps: stacks in slots 0-2")]
    return check


@scenario("tidy-groups-together", "tidy", 150, "An overflow chest with mixed items is tidied after a delivery: like items sit together, renamed items aren't merged.")
def _(c):
    c.chest("copper", 2, 10, [("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("overflow", 12, 10, [("dirt", 5), ("bread", 3), ("granite", 4), ("diamond", 2), ("dirt", 6),
                                 ('dirt[custom_name="Special"]', 2), ("cobblestone", 8), ("bread", 7)])
    c.sign(12, 10, "[Overflow]")
    c.golem(4, 10)

    def check():
        got = slots(c, "overflow")
        plain_dirt = [k for _, i, k, n in got if i == "dirt" and not n]
        named_dirt = [k for _, i, k, n in got if i == "dirt" and n]
        return [(any(i == "sculk_shrieker" for _, i, _, _ in got), f"shrieker delivered ({show(got)})"),
                (plain_dirt == [11], "plain dirt merged into one stack of 11"),
                (named_dirt == [2], "renamed dirt kept as its own stack"),
                ([k for _, i, k, _ in got if i == "bread"] == [10], "bread merged into one stack of 10"),
                (together(got, {"granite", "cobblestone"}), "granite and cobblestone (stone group) side by side"),
                (together(got, {"dirt"}), "both dirt stacks side by side"),
                (packed(got), "no gaps")]
    return check


@scenario("tidy-off", "tidy", 150, "tidy-chests: false: a delivery leaves the chest's split stacks alone.", alone=True)
def _(c):
    set_options(c, tidy_chests="false")
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 12, 10, [("oak_log", 10), ("air", 1), ("oak_log", 20)])
    c.sign(12, 10, "[logs]")
    c.golem(4, 10)
    state = {}

    def check():
        got = slots(c, "logs")
        delivered = sum(k for _, i, k, _ in got if i == "birch_log") == 16
        if delivered:
            state.setdefault("at", c.elapsed())
        waited = delivered and c.elapsed() - state["at"] > 10  # longer than the tidy delay
        return [(delivered, f"birch logs delivered ({show(got)})"), (waited, "waited 10s after delivery"),
                ([k for _, i, k, _ in got if i == "oak_log"] == [10, 20], "oak logs still split 10 + 20")]
    return check


def stacks(c, name, item):
    """Stack sizes of an item across every block of a named chest."""
    return sorted((k for b in range(len(c.chests[name])) for _, i, k, _ in slots(c, name, b) if i == item), reverse=True)


@scenario("tidy-double-chest", "tidy", 150, "A double chest with split stacks in both halves is tidied as one: each item ends up in a single stack.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 12, 10, [("oak_log", 10), ("air", 1), ("oak_log", 5), ("spruce_log", 1)], half="left")
    c.chest("logs", 11, 10, [("oak_log", 20), ("spruce_log", 2), ("air", 1), ("oak_log", 7)], half="right")
    c.sign(12, 10, "[logs]")
    c.golem(4, 10)
    return lambda: [(stacks(c, "logs", "birch_log") == [16], f"birch: {stacks(c, 'logs', 'birch_log')} (want [16])"),
                    (stacks(c, "logs", "oak_log") == [42], f"oak: {stacks(c, 'logs', 'oak_log')} (want [42])"),
                    (stacks(c, "logs", "spruce_log") == [3], f"spruce: {stacks(c, 'logs', 'spruce_log')} (want [3])")]


@scenario("tidy-stack-sizes", "tidy", 150, "Merging respects each item's stack size: eggs stack to 16, swords don't stack at all.")
def _(c):
    c.chest("copper", 2, 10, [("egg", 16)], block="waxed_copper_chest")
    c.chest("eggs", 12, 10, [("egg", 10), ("iron_sword", 1), ("egg", 10), ("iron_sword", 1), ("egg", 10)])
    c.sign(12, 10, "[egg]")
    c.golem(4, 10)
    return lambda: [(stacks(c, "eggs", "egg") == [16, 16, 14], f"eggs: {stacks(c, 'eggs', 'egg')} (want [16, 16, 14])"),
                    (stacks(c, "eggs", "iron_sword") == [1, 1], f"swords: {stacks(c, 'eggs', 'iron_sword')} (want [1, 1])")]


@scenario("tidy-keeps-item-data", "tidy", 150, "Tidying an overflow chest keeps a shulker box's contents, enchantments, damage and names intact.")
def _(c):
    shulker = 'white_shulker_box[minecraft:container=[{slot:0,item:{id:"minecraft:diamond",count:5}}]]'
    book = 'enchanted_book[minecraft:stored_enchantments={"minecraft:mending":1}]'
    sword = 'iron_sword[minecraft:damage=100,minecraft:custom_name="Old Faithful"]'
    c.chest("copper", 2, 10, [("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("overflow", 12, 10, [("dirt", 3), (shulker, 1), ("dirt", 3), (book, 1), ("air", 1), (sword, 1), ("dirt", 3)])
    c.sign(12, 10, "[Overflow]")
    c.golem(4, 10)

    def inner(path):
        x, y, z = c.chests["overflow"][0]
        m = re.search(r"data: (\d+)", c.r.cmd(f"data get block {x} {y} {z} {path}"))
        return int(m.group(1)) if m else -1

    def check():
        got = slots(c, "overflow")
        diamonds = inner('Items[{id:"minecraft:white_shulker_box"}].components."minecraft:container"[0].item.count')
        mending = inner('Items[{id:"minecraft:enchanted_book"}].components."minecraft:stored_enchantments"."minecraft:mending"')
        damage = inner('Items[{id:"minecraft:iron_sword"}].components."minecraft:damage"')
        named = any(i == "iron_sword" and n for _, i, _, n in got)
        return [(any(i == "sculk_shrieker" for _, i, _, _ in got), "shrieker delivered"),
                (stacks(c, "overflow", "dirt") == [9], f"dirt merged: {stacks(c, 'overflow', 'dirt')}"),
                (diamonds == 5, f"shulker box holds {diamonds} diamonds (want 5)"),
                (mending == 1, f"book still has mending {mending} (want 1)"),
                (damage == 100 and named, f"sword damage {damage} (want 100), still named: {named}")]
    return check


@scenario("tidy-chest-broken", "tidy", 120, "The chest is broken right after a delivery, before its tidy runs: no errors, nothing else happens.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 12, 10, [("oak_log", 10), ("air", 1), ("oak_log", 5)])
    c.sign(12, 10, "[logs]")
    c.golem(4, 10)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.count("logs", "birch_log") > 0, lambda: c.remove(12, 10))
        return [(t.at is not None, "chest broken right after the delivery"),
                (t.since() > 10, f"waited {t.since():.0f}s of 10s past the tidy delay")]
    return check
