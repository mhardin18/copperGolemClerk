"""carry: carry-amount, how many items a golem carries per trip.

Each scenario sets carry-amount and records the most items any golem on the platform
held at once (checked about every second), as well as where the items ended up.
"""
from arena import expect, scenario
from suite_options import set_options


def watch_hands(c, item):
    """A function that returns the most of item a golem on the platform has held so far."""
    most = [0]

    def seen():
        most[0] = max(most[0], c.holding(item))
        return most[0]
    return seen


@scenario("carry-64", "carry", 180, "carry-amount: 64: the golem takes 64 birch logs from the copper chest at once and sorts all 128 in two trips.", alone=True)
def _(c):
    set_options(c, carry_amount="64")
    c.chest("copper", 2, 10, [("birch_log", 64), ("birch_log", 64)], block="waxed_copper_chest")
    c.chest("logs", 14, 10, [("oak_log", 1)])
    c.golem(4, 10)
    most = watch_hands(c, "birch_log")
    return lambda: [(most() == 64, f"most held at once: {most()} (want 64)"),
                    expect(c, "logs", "birch_log", 128), expect(c, "copper", "birch_log", 0)]


@scenario("carry-4", "carry", 240, "carry-amount: 4: the golem puts the extra back and carries 4 logs per trip; all 12 still get sorted.", alone=True)
def _(c):
    set_options(c, carry_amount="4")
    c.chest("copper", 2, 10, [("birch_log", 12)], block="waxed_copper_chest")
    c.chest("logs", 8, 10, [("oak_log", 1)])
    c.golem(4, 10)
    most = watch_hands(c, "birch_log")
    return lambda: [(0 < most() <= 4, f"most held at once: {most()} (want 1-4)"),
                    expect(c, "logs", "birch_log", 12), expect(c, "copper", "birch_log", 0)]


@scenario("carry-stack-size", "carry", 180, "carry-amount: 64 with ender pearls (they stack to 16): the golem carries 16 at most, and all 32 are sorted.", alone=True)
def _(c):
    set_options(c, carry_amount="64")
    c.chest("copper", 2, 10, [("ender_pearl", 16), ("ender_pearl", 16)], block="waxed_copper_chest")
    c.chest("pearls", 10, 10, [("ender_pearl", 1)])
    c.golem(4, 10)
    most = watch_hands(c, "ender_pearl")
    return lambda: [(0 < most() <= 16, f"most held at once: {most()} (want 1-16)"),
                    expect(c, "pearls", "ender_pearl", 33)]


@scenario("carry-overflow-64", "carry", 180, "carry-amount: 64: an idle golem fetches 64 granite from [Overflow] in one go.", alone=True)
def _(c):
    set_options(c, carry_amount="64")
    c.chest("copper", 2, 10, block="waxed_copper_chest")  # empty: nothing else to do
    c.chest("overflow", 8, 10, [("granite", 64)])
    c.sign(8, 10, "[Overflow]")
    c.chest("stone", 14, 10, [("andesite", 1)])
    c.golem(4, 10)
    most = watch_hands(c, "granite")
    return lambda: [(most() == 64, f"most held at once: {most()} (want 64)"),
                    expect(c, "stone", "granite", 64), expect(c, "overflow", "granite", 0)]


@scenario("carry-bad-value", "carry", 30, "carry-amount: 500 is out of range: a warning, and 64 is used.", alone=True)
def _(c):
    set_options(c, carry_amount="500")
    return lambda: [(any("carry-amount must be 1 to 64" in l for l in c.log_lines()), "warned about carry-amount 500")]
