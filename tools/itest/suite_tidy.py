"""tidy: golems tidy the chest they put something in (tidy-chests).

Layout: copper chest at (2,10), golem at (4,10), the chest under test further along +x.
"""
import re

from arena import scenario
from suite_options import set_options


def slots(c, name):
    """[(slot, id, count, named)] of the first block of a named chest, in list order."""
    x, y, z = c.chests[name][0]
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
