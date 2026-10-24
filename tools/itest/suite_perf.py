"""perf: lots of chests in range must not slow the server down.

Builds 363 chests in three layers above the platform (out of reach, but inside the
golems' search area, so every chest choice scans them all), 90 of them with sign
labels, then has six golems sort 96 birch logs into the one reachable log chest.
Paper's /mspt (milliseconds per tick; 50 is the budget for 20 ticks a second) is
sampled before building and while the golems work.
"""
import re

from arena import expect, scenario

LABELS = ["[food]", "[ore]", "[tools]", "[redstone]", "[wool]"]


def birch_conserved(c):
    """Like arena.conserved, but skips the 363 shelf chests (birch can't be there, and
    querying them every poll would add load and skew the tick-time measurement)."""
    got = c.count("copper", "birch_log") + c.count("logs", "birch_log") + c.holding("birch_log") + c.dropped("birch_log")
    return got == 96, f"{got} birch logs in chests, hands and on the ground (want 96: none lost or duplicated)"


def mspt(c):
    """Average milliseconds per tick over the last 5 seconds."""
    out = re.sub(r"§.", "", c.r.cmd("mspt"))
    m = re.search(r"◴\s*([\d.]+)/", out) or re.search(r":\s*\n?\s*\S*\s*([\d.]+)/", out)
    return float(m.group(1)) if m else None


@scenario("many-chests-tick-time", "perf", 360, "363 chests (90 labelled) in range, 6 golems working: ticks stay under 40 ms and within 20 ms of the baseline.",
          alone=True)
def _(c):
    baseline = mspt(c)
    shelf = 0
    for dy in (3, 5, 7):
        for dx in range(0, 21, 2):
            for dz in range(0, 21, 2):
                c.chest("shelf", dx, dz, [("stone", 1)], dy=dy)
                if shelf % 4 == 0:
                    c.sign(dx, dz, LABELS[(shelf // 4) % len(LABELS)], dy=dy)
                shelf += 1
    c.chest("copper", 2, 10, [("birch_log", 16)] * 6, block="waxed_copper_chest")
    c.chest("logs", 16, 10, [("oak_log", 1)])
    for dz in (4, 6, 8, 12, 14, 16):
        c.golem(4, dz)
    samples = []

    def check():
        if c.elapsed() >= 20 and len(samples) < 6 and int(c.elapsed()) % 6 < 3:
            value = mspt(c)
            if value is not None:
                samples.append(value)
        worst = max(samples) if samples else None
        return [(len(samples) >= 3, f"tick time samples while working: {samples} ms (baseline {baseline} ms)"),
                (worst is not None and worst < 40, f"worst 5-second average {worst} ms (want under 40)"),
                (worst is not None and baseline is not None and worst - baseline < 20,
                 f"slowdown {None if worst is None or baseline is None else round(worst - baseline, 1)} ms (want under 20)"),
                expect(c, "logs", "birch_log", 96), birch_conserved(c)]
    return check


@scenario("many-idle-golems-scan", "perf", 150, "20 idle golems, 363 chests in range, 20 unhomed items in overflow: the 5-second fetch checks keep ticks under 40 ms.",
          alone=True)
def _(c):
    baseline = mspt(c)
    shelf = 0
    for dy in (3, 5, 7):
        for dx in range(0, 21, 2):
            for dz in range(0, 21, 2):
                c.chest("shelf", dx, dz, [("stone", 1)], dy=dy)
                if shelf % 4 == 0:
                    c.sign(dx, dz, LABELS[(shelf // 4) % len(LABELS)], dy=dy)
                shelf += 1
    unhomed = ["sculk_shrieker", "bedrock", "cow_spawn_egg", "sponge", "bell", "lodestone", "conduit", "beacon",
               "dragon_egg", "nether_star", "heavy_core", "goat_horn", "echo_shard", "totem_of_undying",
               "heart_of_the_sea", "trial_key", "turtle_egg", "sniffer_egg", "elytra", "name_tag"]
    c.double_chest("overflow", 11, 2, [(item, 1) for item in unhomed])
    c.sign(11, 2, "[Overflow]")
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    for n in range(20):
        c.golem(3 + (n % 10) * 1, 8 + (n // 10) * 4)
    samples = []

    def check():
        if c.elapsed() >= 15 and len(samples) < 8 and int(c.elapsed()) % 6 < 3:
            value = mspt(c)
            if value is not None:
                samples.append(value)
        worst = max(samples) if samples else None
        return [(len(samples) >= 5, f"tick time samples: {samples} ms (baseline {baseline} ms)"),
                (worst is not None and worst < 40, f"worst 5-second average {worst} ms (want under 40)"),
                (worst is not None and baseline is not None and worst - baseline < 20,
                 f"slowdown {None if worst is None or baseline is None else round(worst - baseline, 1)} ms (want under 20)")]
    return check
