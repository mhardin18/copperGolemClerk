"""stats: /cgc stats counts what golems sort (each delivery once, though Paper asks twice)."""
import os
import re

from arena import SERVER, Trigger, expect, scenario

STATS_FILE = SERVER + "/plugins/CopperGolemClerk/stats.yml"


def today(c):
    reply = re.sub(r"§.", "", c.r.cmd("cgc stats"))
    m = re.search(r"today ([\d,]+)", reply)
    return int(m.group(1).replace(",", "")) if m else -1


@scenario("stats-counts-deliveries", "stats", 150, "A golem sorts 32 birch logs (two trips): /cgc stats' count for today goes up by exactly 32.", alone=True)
def _(c):
    before = today(c)
    c.chest("copper", 2, 10, [("birch_log", 16), ("birch_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 10, 10)
    c.sign(10, 10, "[logs]")
    c.golem(4, 10)
    done = Trigger()

    def check():
        done.fire_when(lambda: c.count("logs", "birch_log") == 32, lambda: None)
        now = today(c)
        return [expect(c, "logs", "birch_log", 32), (done.since() > 8, "waited 8s after the last delivery"),
                (before >= 0, f"stats readable (before: {before})"),
                (now - before == 32, f"today went up by {now - before} (want 32)")]
    return check


@scenario("stats-saved", "stats", 400, "Stats are saved to stats.yml (every 5 minutes) with the items sorted.", alone=True)
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 4)], block="waxed_copper_chest")
    c.chest("logs", 10, 10)
    c.sign(10, 10, "[logs]")
    c.golem(4, 10)

    def check():
        text = open(STATS_FILE).read() if os.path.isfile(STATS_FILE) else ""
        return [("birch_log" in text, "stats.yml lists birch_log"), ("unclaimed" in text, "under unclaimed")]
    return check
