"""The parts of the performance tool that don't touch a server: parsing, statistics, the
arena layout, comparing two runs and estimating how long a run takes. Tested by
test_perflib.py (python3 -I -m unittest discover -s tools/perf)."""
import math
import re
import statistics

# ---- arena layout (block coordinates in pluginDev_world, far from the itest platforms at x=-500)
CX, CZ = 3000, 3000      # centre of the perf area
FY = 230                 # floor level (golems stand here); above any terrain at this spot
UNIT_STEP = 4            # units sit on a 4-block grid
UNIT_COLUMNS = 15        # 15 x 14 = 210 units: room for 200 golems
UNIT_ROWS = 14
FAR = 128                # the largest search-distance: filler chests and cauldrons reach this far
FILLER_STEP = 6          # one filler chest every 6 blocks: 43 x 43 = 1,849 chests out to 128
FILLER_DY = 4            # filler chests hang 4 above the floor: in search range, out of reach (row 4)

# What counts as a sample to distrust: the CPU too hot, or even its fastest core slowed (heat or
# power limits; idle cores drop to 800 MHz on their own), or survival busy.
HOT_C = 90
SLOW_MHZ = 3300
WARM_C = 80        # a slow clock only counts when the CPU is this hot: at light load it idles slow anyway
SURVIVAL_BUSY_MSPT = 20
NOISY_SHARE = 0.2        # a point is noisy if this share of its samples is

# What counts as worse in a comparison: both this much slower and this many ms.
WORSE_RATIO = 1.10
WORSE_MS = 0.5

SETUP_SECONDS = 25       # summoning, resetting the units, settling (a guess; the run prints the real total)


def strip_colours(text):
    return re.sub(r"§.", "", text)


def paper_version(text):
    """Paper's version from a /version reply, or None while it is still checking."""
    m = re.search(r"running (Paper version \S+)", strip_colours(text))
    return m.group(1) if m else None


def parse_mspt(text):
    """Paper's /mspt reply -> (avg, min, max) over the last 5 seconds, in ms, or None."""
    nums = re.findall(r"(\d+(?:\.\d+)?)/(\d+(?:\.\d+)?)/(\d+(?:\.\d+)?)", strip_colours(text))
    if not nums:
        return None
    return tuple(float(x) for x in nums[0])


def parse_players(text):
    m = re.search(r"There are (\d+)", strip_colours(text))
    return int(m.group(1)) if m else None


def percentile(values, p):
    """Nearest-rank percentile (p in 0..100) of a non-empty list."""
    ordered = sorted(values)
    k = max(0, min(len(ordered) - 1, math.ceil(p / 100 * len(ordered)) - 1))
    return ordered[k]


def summarize(values):
    values = [v for v in values if v is not None]
    if not values:
        return {"n": 0, "median": None, "mean": None, "p95": None}
    return {"n": len(values), "median": round(statistics.median(values), 2),
            "mean": round(statistics.fmean(values), 2), "p95": round(percentile(values, 95), 2)}


def slope_per_hour(times, values):
    """Least-squares slope of values over times (seconds), per hour; None with fewer than 3 points."""
    pts = [(t, v) for t, v in zip(times, values) if v is not None]
    if len(pts) < 3:
        return None
    mt = statistics.fmean(t for t, _ in pts)
    mv = statistics.fmean(v for _, v in pts)
    den = sum((t - mt) ** 2 for t, _ in pts)
    if den == 0:
        return None
    return round(sum((t - mt) * (v - mv) for t, v in pts) / den * 3600, 3)


def noise(samples):
    """Why a point's samples can't be trusted (a list of reasons; empty = fine)."""
    if not samples:
        return ["no samples"]
    checks = [
        ("CPU at or over %d°C" % HOT_C, lambda s: (s.get("temp_c") or 0) >= HOT_C),
        ("CPU hot and held below %d MHz" % SLOW_MHZ,
         lambda s: s.get("mhz_top") is not None and s["mhz_top"] < SLOW_MHZ and (s.get("temp_c") or 0) >= WARM_C),
        ("players on survival", lambda s: (s.get("survival_players") or 0) > 0),
        ("survival over %d ms per tick" % SURVIVAL_BUSY_MSPT, lambda s: (s.get("survival_mspt") or 0) > SURVIVAL_BUSY_MSPT),
    ]
    reasons = []
    for text, bad in checks:
        share = sum(1 for s in samples if bad(s)) / len(samples)
        if share > NOISY_SHARE:
            reasons.append(f"{text} in {share:.0%} of samples")
    return reasons


def unit_positions():
    """(x, z) of every unit's copper chest, row by row."""
    x0 = CX - (UNIT_COLUMNS - 1) * UNIT_STEP // 2
    z0 = CZ - (UNIT_ROWS - 1) * UNIT_STEP // 2
    return [(x0 + c * UNIT_STEP, z0 + r * UNIT_STEP) for r in range(UNIT_ROWS) for c in range(UNIT_COLUMNS)]


def spread(units, n):
    """n units picked evenly from the list (so a few golems don't all crowd one corner)."""
    if n <= 0:
        return []
    if n >= len(units):
        return list(units)
    return [units[i * len(units) // n] for i in range(n)]


def unit_bounds(margin=3):
    """(x1, z1, x2, z2) of the floor under all the units, with a margin."""
    pos = unit_positions()
    xs = [x for x, _ in pos]
    zs = [z for _, z in pos]
    return min(xs) - margin, min(zs) - margin, max(xs) + 1 + margin, max(zs) + 2 + margin


def filler_positions():
    """(x, z) of the filler chests: a grid out to FAR blocks each way from the centre."""
    steps = range(-(FAR // FILLER_STEP) * FILLER_STEP, FAR + 1, FILLER_STEP)
    return [(CX + dx, CZ + dz) for dz in steps for dx in steps]


def chunk_centres(radius):
    """(x, z) of the centre of every chunk that overlaps the square of this radius round the centre."""
    c1, c2 = (CX - radius) >> 4, (CX + radius) >> 4
    d1, d2 = (CZ - radius) >> 4, (CZ + radius) >> 4
    return [(cx * 16 + 8, cz * 16 + 8) for cz in range(d1, d2 + 1) for cx in range(c1, c2 + 1)]


def forceload_strips(radius, limit=256):
    """forceload areas (x1, z1, x2, z2) covering the square of this radius, each under the
    server's 256-chunk limit per command."""
    c1, c2 = (CX - radius) >> 4, (CX + radius) >> 4
    d1, d2 = (CZ - radius) >> 4, (CZ + radius) >> 4
    width = c2 - c1 + 1
    rows = max(1, limit // width)
    strips = []
    for d in range(d1, d2 + 1, rows):
        last = min(d2, d + rows - 1)
        strips.append((c1 * 16, d * 16, c2 * 16 + 15, last * 16 + 15))
    return strips


# ---- what a run does

def plan(tests, opts):
    """The points a run measures, in order: dicts with test, mode, params."""
    points = []
    for test in tests:
        if test == "baseline":
            points.append({"test": "baseline", "mode": "-", "params": {}})
        elif test == "golems":
            for mode in opts["modes"]:
                for n in opts["counts"]:
                    points.append({"test": "golems", "mode": mode, "params": {"golems": n}})
        elif test == "search":
            if "vanilla" in opts["modes"]:
                points.append({"test": "search", "mode": "vanilla", "params": {"golems": opts["search_golems"], "distance": 32}})
            if "plugin" in opts["modes"]:
                for d in opts["distances"]:
                    points.append({"test": "search", "mode": "plugin", "params": {"golems": opts["search_golems"], "distance": d}})
        elif test == "cauldrons":
            for cauldrons in (False, True):
                for d in opts["cauldron_distances"]:
                    points.append({"test": "cauldrons", "mode": "plugin",
                                   "params": {"golems": opts["search_golems"], "distance": d, "cauldrons": cauldrons}})
        elif test == "chests":
            points.append({"test": "chests", "mode": "plugin", "params": {}})
        elif test == "soak":
            points.append({"test": "soak", "mode": "plugin", "params": {"golems": opts["soak_golems"], "minutes": opts["soak_minutes"]}})
        else:
            raise ValueError(f"unknown test: {test}")
    return points


# How long the one-off builds take (a guess, seconds): the first run also generates chunks.
BUILD_SECONDS = {"golems": 60, "search": 120, "cauldrons": 90}
CHESTS_SECONDS = 11 * 60  # the two itest perf scenarios, with their setup


def estimate_seconds(points, opts):
    total, built = 0, set()
    for p in points:
        if p["test"] in BUILD_SECONDS and p["test"] not in built:
            total += BUILD_SECONDS[p["test"]]
            built.add(p["test"])
        if p["test"] == "chests":
            total += CHESTS_SECONDS
        elif p["test"] == "soak":
            total += SETUP_SECONDS + opts["warmup"] + p["params"]["minutes"] * 60
        else:
            total += (SETUP_SECONDS + opts["warmup"] + opts["measure"]) * opts["reps"]
    return total


def key(point):
    return (point["test"], point["mode"], tuple(sorted(point["params"].items())))


def label(point):
    params = ", ".join(f"{k} {v}" for k, v in point["params"].items())
    return f"{point['test']} [{point['mode']}]" + (f" {params}" if params else "")


def compare(old, new):
    """Rows (label, old median, new median, change, worse) for points both runs measured."""
    before = {key(p): p for p in old if p.get("summary", {}).get("median") is not None}
    rows = []
    for p in new:
        o = before.get(key(p))
        m = p.get("summary", {}).get("median")
        if o is None or m is None:
            continue
        om = o["summary"]["median"]
        worse = m > om * WORSE_RATIO and m - om > WORSE_MS
        change = f"{(m - om) / om:+.0%}" if om else "n/a"
        rows.append((label(p), om, m, change, worse))
    return rows
