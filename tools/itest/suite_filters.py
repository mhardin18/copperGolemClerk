"""filters: [Only ...] signs on copper chests (golems take only those items out)."""
import re

from arena import Trigger, expect, scenario


@scenario("filter-only-logs", "filters", 150, "A copper chest with granite first and birch logs after, signed [Only Logs]: the logs are sorted, the granite stays.")
def _(c):
    c.chest("copper", 2, 10, [("granite", 16), ("birch_log", 16)], block="waxed_copper_chest")
    c.sign(2, 10, "[Only Logs]")
    c.chest("logs", 10, 10)
    c.sign(10, 10, "[logs]")
    c.chest("stone", 14, 10)
    c.sign(14, 10, "[stone]")
    c.golem(4, 10)
    done = Trigger()

    def check():
        done.fire_when(lambda: c.count("logs", "birch_log") == 16, lambda: None)
        return [expect(c, "logs", "birch_log", 16), (done.since() > 15, "waited 15s more"),
                expect(c, "copper", "granite", 16), expect(c, "stone", "*", 0),
                (c.holding() == 0, f"golem holding {c.holding()} (want 0)")]
    return check


@scenario("filter-nothing-matches", "filters", 60, "A copper chest of granite signed [Only Logs]: golems take nothing from it.")
def _(c):
    c.chest("copper", 2, 10, [("granite", 16)], block="waxed_copper_chest")
    c.sign(2, 10, "[only logs]")
    c.chest("stone", 10, 10)
    c.sign(10, 10, "[stone]")
    c.golem(4, 10)
    t = Trigger()

    def check():
        t.fire_when(lambda: True, lambda: None)
        return [(t.since() > 30, "waited 30s"), expect(c, "copper", "granite", 16), expect(c, "stone", "*", 0),
                (c.holding() == 0, "golem took nothing")]
    return check


@scenario("filter-two-lines", "filters", 180, "[Only Logs] and [Only Stone] on one copper chest: both are sorted; the kelp stays.")
def _(c):
    c.chest("copper", 2, 10, [("kelp", 8), ("birch_log", 8), ("granite", 8)], block="waxed_copper_chest")
    c.sign(2, 10, ["[Only Logs]", "[Only Stone]"])
    c.chest("logs", 10, 10)
    c.sign(10, 10, "[logs]")
    c.chest("stone", 14, 10)
    c.sign(14, 10, "[stone]")
    c.chest("overflow", 18, 10)
    c.sign(18, 10, "[Overflow]")
    c.golem(4, 10)
    return lambda: [expect(c, "logs", "birch_log", 8), expect(c, "stone", "granite", 8),
                    expect(c, "copper", "kelp", 8), expect(c, "overflow", "*", 0)]


@scenario("filter-check-command", "filters", 30, "/cgc check on a filtered copper chest lists the filter and how many items it leaves; on a plain chest it says the filter does nothing.")
def _(c):
    c.chest("copper", 2, 10, [("granite", 5), ("birch_log", 3)], block="waxed_copper_chest")
    c.sign(2, 10, "[Only Logs]")
    c.chest("plain", 10, 10)
    c.sign(10, 10, "[Only Logs]")

    def say(dx, dz):
        x, y, z = c.abs(dx, dz)
        return re.sub(r"§.", "", c.r.cmd(f"cgc check {x} {y} {z}"))

    def check():
        copper, plain = say(2, 10), say(10, 10)
        return [("golems take only these items" in copper, f"copper chest: filter shown: {copper[:160]!r}"),
                ("5 items in it don't match" in copper, "says 5 items are left behind"),
                ("does nothing on this chest" in plain, f"plain chest: says it does nothing: {plain[:160]!r}")]
    return check
