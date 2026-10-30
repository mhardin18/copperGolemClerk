"""alerts: players are told about full labelled chests and filling [Overflow] chests.

With no player in a claim (as on the test platforms) an alert goes to players with
coppergolemclerk.alerts and is always logged as "Alert...: <text>"; these scenarios read
the log.
"""
from arena import Trigger, scenario
from suite_options import set_options


def alerts(c, text=""):
    return [l for l in c.log_lines() if "Alert" in l and text in l and c.in_cell(l)]


def full_logs_chest(c):
    """Copper chest of birch logs, a [logs] chest with no room, an [Overflow] chest."""
    c.chest("copper", 2, 10, [("birch_log", 16), ("birch_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 10, 10, [("oak_log", 64)] * 27)
    c.sign(10, 10, "[logs]")
    c.chest("overflow", 16, 10)
    c.sign(16, 10, "[Overflow]")
    c.golem(4, 10)


@scenario("alert-full-label-chest", "alerts", 150, "The [logs] chest is full when a golem brings birch logs: an alert names the chest and the item, once (cooldown), and the logs go to [Overflow].")
def _(c):
    full_logs_chest(c)
    x, y, z = c.abs(10, 10)

    def check():
        got = alerts(c, "is full")
        return [(len(got) >= 1, "an alert about the full chest"),
                (any(f"[logs] chest at {x}, {y}, {z} is full" in l for l in got), f"names the [logs] chest at {x}, {y}, {z}"),
                (any("birch log" in l for l in got), "names the item"),
                (c.count("overflow", "birch_log") == 32, f"overflow has {c.count('overflow', 'birch_log')} birch logs (want 32: both trips)"),
                (len(got) == 1, f"only one alert across both trips (got {len(got)})")]
    return check


@scenario("alert-overflow-filling", "alerts", 120, "An [Overflow] chest with 25 of 27 slots used when a golem brings an item with no home: alert says it's 92% full.")
def _(c):
    c.chest("copper", 2, 10, [("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("overflow", 10, 10, [("cobweb", 1)] * 25)
    c.sign(10, 10, "[Overflow]")
    c.golem(4, 10)
    x, y, z = c.abs(10, 10)
    return lambda: [(any(f"[Overflow] chest at {x}, {y}, {z} is 92% full" in l for l in alerts(c)),
                     f"alert: [Overflow] chest at {x}, {y}, {z} is 92% full"),
                    (c.count("overflow", "sculk_shrieker") == 1, "the shrieker still goes in")]


@scenario("alert-overflow-full", "alerts", 120, "A completely full [Overflow] chest: alert says it's full.")
def _(c):
    c.chest("copper", 2, 10, [("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("overflow", 10, 10, [("cobweb", 64)] * 27)
    c.sign(10, 10, "[Overflow]")
    c.golem(4, 10)
    x, y, z = c.abs(10, 10)
    return lambda: [(any(f"[Overflow] chest at {x}, {y}, {z} is full." in l for l in alerts(c)),
                     f"alert: [Overflow] chest at {x}, {y}, {z} is full")]


@scenario("alert-room-no-alert", "alerts", 60, "A [logs] chest with room and an [Overflow] chest under the threshold: logs delivered, no alerts.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 10, 10, [("oak_log", 64)] * 20)
    c.sign(10, 10, "[logs]")
    c.chest("overflow", 16, 10, [("cobweb", 1)] * 10)
    c.sign(16, 10, "[Overflow]")
    c.golem(4, 10)
    done = Trigger()

    def check():
        done.fire_when(lambda: c.count("logs", "birch_log") == 16, lambda: None)
        return [(done.at is not None, "logs delivered"), (done.since() > 5, "waited 5s more"),
                (not alerts(c), f"no alerts (got {len(alerts(c))})")]
    return check


@scenario("option-full-chest-alerts-off", "alerts", 150, "full-chest-alerts: false: the [logs] chest is full, logs go to [Overflow], no alert.", alone=True)
def _(c):
    set_options(c, full_chest_alerts="false")
    full_logs_chest(c)
    done = Trigger()

    def check():
        done.fire_when(lambda: c.count("overflow", "birch_log") >= 16, lambda: None)
        return [(done.at is not None, "logs went to overflow"), (done.since() > 5, "waited 5s more"),
                (not alerts(c), f"no alerts (got {len(alerts(c))})")]
    return check


@scenario("option-overflow-alert-percent", "alerts", 120, "overflow-alert-percent: 0: an [Overflow] chest at 92% gets no alert (only a full one would).", alone=True)
def _(c):
    set_options(c, overflow_alert_percent="0")
    c.chest("copper", 2, 10, [("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("overflow", 10, 10, [("cobweb", 1)] * 25)
    c.sign(10, 10, "[Overflow]")
    c.golem(4, 10)
    done = Trigger()

    def check():
        done.fire_when(lambda: c.count("overflow", "sculk_shrieker") == 1, lambda: None)
        return [(done.at is not None, "shrieker delivered"), (done.since() > 5, "waited 5s more"),
                (not alerts(c), f"no alerts (got {len(alerts(c))})")]
    return check
