"""Performance tests for Copper Golem Clerk on pluginDev: how much server time golems cost.

    python3 -I tools/perf/perf.py [options] TEST...       (or tools/perf/run-perf.sh, which builds and deploys first)

Tests (see tools/perf/README.md):
    baseline   the server with nothing built: its own tick time
    golems     0 to 200 golems sorting, with the plugin and with it switched off (cost per golem)
    search     20 golems among 1,849 out-of-reach chests at search-distance 32, 64, 96, 128
    cauldrons  20 idle golems with an empty bucket in [Fuel]: the lava cauldron lookup, with and without cauldrons
    chests     the itest perf suite (363 chests, 20 idle golems): pass/fail and tick time
    soak       100 golems sorting for 60 minutes: does tick time or memory creep up?
    all        baseline golems search cauldrons chests (soak only when named)

Each point is reset, warmed up, then sampled every 5 seconds: Paper's /mspt (the 5-second
average), the CPU's temperature and clock, the host's load, the server's memory and CPU use,
and survival's players and tick time. Results go to ~/claude/perf-results/<time>-<label>/.
"""
import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ITEST = os.path.join(os.path.dirname(HERE), "itest")
sys.path.insert(0, HERE)
sys.path.insert(0, ITEST)
import perflib as L  # noqa: E402
from rcon import from_properties  # noqa: E402

SERVER = "/opt/minecraft/pluginDev"
SURVIVAL = "/opt/minecraft/survival"
CONFIG = SERVER + "/plugins/CopperGolemClerk/config.yml"
WORLD = "pluginDev_world"
RESULTS = os.path.expanduser("~/claude/perf-results")
SAMPLE_SECONDS = 5
SOAK_SAMPLE_SECONDS = 30
LUCK = '{id:"minecraft:luck",duration:-1,show_particles:0b,show_icon:0b}'
BIRCH = ",".join(f'{{Slot:{s}b,id:"minecraft:birch_log",count:64}}' for s in range(27))


def log(text):
    print(f"[{time.strftime('%H:%M:%S')}] {text}", flush=True)


class Server:
    """RCON to pluginDev (and, if it answers, survival), with one reconnect on a dropped link."""

    def __init__(self):
        self.r = from_properties(SERVER + "/server.properties")
        self.s = None
        self.s_failed = False

    def cmd(self, command):
        try:
            return self.r.cmd(command)
        except (OSError, ConnectionError):
            self.r = from_properties(SERVER + "/server.properties")
            return self.r.cmd(command)

    def run(self, command):
        out = self.cmd(command)
        bad = ("Unknown", "Incorrect", "Expected", "Invalid", "not loaded", "Could not", "Failed", "Only one")
        if any(b in out for b in bad):
            raise RuntimeError(f"command failed: {command}\n  -> {out}")
        return out

    def survival(self, command):
        if self.s_failed:
            return None
        try:
            if self.s is None:
                self.s = from_properties(SURVIVAL + "/server.properties")
            return self.s.cmd(command)
        except (OSError, ConnectionError, KeyError):
            self.s, self.s_failed = None, True  # survival down or unreadable: don't keep trying
            return None


# ---- host readings

def hwmon(name):
    for h in sorted(os.listdir("/sys/class/hwmon")):
        try:
            if open(f"/sys/class/hwmon/{h}/name").read().strip() == name:
                return f"/sys/class/hwmon/{h}"
        except OSError:
            pass
    return None


CORETEMP = hwmon("coretemp")


def cpu_temp():
    try:
        return int(open(CORETEMP + "/temp1_input").read()) / 1000 if CORETEMP else None
    except OSError:
        return None


def cpu_mhz():
    """(fastest core, average) clock in MHz. Idle cores drop to 800 MHz to save power, so the
    fastest core is the one that shows heat or power limits: when it's slow, every core is."""
    mhz = [float(l.split(":")[1]) for l in open("/proc/cpuinfo") if l.startswith("cpu MHz")]
    return (round(max(mhz)), round(sum(mhz) / len(mhz))) if mhz else (None, None)


def throttles():
    total = 0
    base = "/sys/devices/system/cpu"
    for cpu in os.listdir(base):
        try:
            total += int(open(f"{base}/{cpu}/thermal_throttle/package_throttle_count").read())
        except (OSError, ValueError):
            pass
    return total


def server_pid():
    out = subprocess.run(["systemctl", "show", "mcd", "-p", "MainPID", "--value"], capture_output=True, text=True).stdout
    return int(out.strip() or 0) or None


def proc_stats(pid):
    """(resident memory in MB, CPU seconds used so far) of the server process."""
    try:
        rss = next(int(l.split()[1]) for l in open(f"/proc/{pid}/status") if l.startswith("VmRSS")) / 1024
        fields = open(f"/proc/{pid}/stat").read().rsplit(")", 1)[1].split()
        cpu = (int(fields[11]) + int(fields[12])) / os.sysconf("SC_CLK_TCK")
        return round(rss), cpu
    except (OSError, StopIteration, IndexError, ValueError):
        return None, None


class Sampler:
    def __init__(self, srv, selector):
        self.srv, self.selector = srv, selector
        self.pid = server_pid()
        self.last = None

    def sample(self, t0):
        mspt = L.parse_mspt(self.srv.cmd("mspt"))
        mhz_top, mhz_avg = cpu_mhz()
        rss, cpu = proc_stats(self.pid)
        now = time.time()
        cpu_pct = None
        if self.last and cpu is not None and now > self.last[0]:
            cpu_pct = round((cpu - self.last[1]) / (now - self.last[0]) * 100)
        self.last = (now, cpu)
        holding = None
        if self.selector:
            out = self.srv.cmd(f"execute if items entity {self.selector} weapon.mainhand *")
            holding = count(out)
        s_mspt = self.srv.survival("mspt")
        s_list = self.srv.survival("list")
        return {"t": round(now - t0, 1), "mspt": mspt[0] if mspt else None, "mspt_max": mspt[2] if mspt else None,
                "temp_c": cpu_temp(), "mhz_top": mhz_top, "mhz_avg": mhz_avg, "load1": float(open("/proc/loadavg").read().split()[0]),
                "rss_mb": rss, "server_cpu_pct": cpu_pct, "holding": holding,
                "survival_players": L.parse_players(s_list) if s_list else None,
                "survival_mspt": (L.parse_mspt(s_mspt) or (None,))[0] if s_mspt else None}


# ---- the arena

UNITS_RADIUS = max(abs(v - c) for v, c in zip(L.unit_bounds(), (L.CX, L.CZ, L.CX, L.CZ))) + 8


def golem_selector():
    x1, z1, x2, z2 = L.unit_bounds(margin=6)
    return f"@e[type=minecraft:copper_golem,x={x1},y={L.FY - 6},z={z1},dx={x2 - x1},dy=20,dz={z2 - z1}]"


def item_selector():
    x1, z1, x2, z2 = L.unit_bounds(margin=6)
    return f"@e[type=minecraft:item,x={x1},y={L.FY - 6},z={z1},dx={x2 - x1},dy=20,dz={z2 - z1}]"


class Arena:
    def __init__(self, srv):
        self.srv = srv
        self.loaded = []       # forceload areas we added
        self.radius = None     # what they cover
        self.built = set()     # "units", "filler", "cauldrons"

    def forceload(self, radius):
        """Keep exactly the chunks within this radius of the centre loaded (so every point of a
        test has the same chunks loaded, whatever ran before)."""
        if radius == self.radius:
            return
        self.unload()
        for area in L.forceload_strips(radius):
            self.srv.run("forceload add {} {} {} {}".format(*area))
            self.loaded.append(area)
        self.radius = radius
        # Wait for the chunks (generated the first time) before building in them.
        for x, z in L.chunk_centres(radius):
            for _ in range(120):
                if "passed" in self.srv.cmd(f"execute if loaded {x} {L.FY} {z}").lower():
                    break
                time.sleep(1)
            else:
                raise RuntimeError(f"chunk at {x}, {z} didn't load in 2 minutes")

    def unload(self):
        for area in self.loaded:
            self.srv.cmd("forceload remove {} {} {} {}".format(*area))
        self.loaded = []
        self.radius = None

    def set(self, x, y, z, block):
        self.srv.cmd(f"setblock {x} {y} {z} minecraft:air")  # setblock refuses a block that's already there
        if block != "air":
            self.srv.run(f"setblock {x} {y} {z} minecraft:{block}")

    def build_units(self):
        x1, z1, x2, z2 = L.unit_bounds()
        if self.radius is None:
            self.forceload(UNITS_RADIUS)
        if "units" in self.built:
            return
        log("building the floor and walls")
        self.srv.run(f"fill {x1} {L.FY} {z1} {x2} {L.FY + 5} {z2} minecraft:air")
        self.srv.run(f"fill {x1} {L.FY - 1} {z1} {x2} {L.FY - 1} {z2} minecraft:glowstone")
        for wall in (f"{x1} {L.FY} {z1} {x2} {L.FY + 2} {z1}", f"{x1} {L.FY} {z2} {x2} {L.FY + 2} {z2}",
                     f"{x1} {L.FY} {z1} {x1} {L.FY + 2} {z2}", f"{x2} {L.FY} {z1} {x2} {L.FY + 2} {z2}"):
            self.srv.run(f"fill {wall} minecraft:glass")
        self.built.add("units")

    def reset_units(self, birch=True, hoppers=True, fuel_chests=0):
        """Every unit as new: a waxed copper chest (full of birch logs, or empty), a hopper
        beside it feeding back into it, and a chest on the hopper where golems put the logs.
        The loop runs with no commands: what golems deliver drains back into the copper chest.
        fuel_chests: that many units (spread out) get a [Fuel] chest with an empty bucket instead."""
        self.clear_golems()
        units = L.unit_positions()
        fuel = set(L.spread(units, fuel_chests))
        items = f"{{Items:[{BIRCH}]}}" if birch else ""
        for x, z in units:
            self.set(x, L.FY, z, f"waxed_copper_chest[facing=south]{items}")
            self.set(x + 1, L.FY, z, "hopper[facing=west]" if hoppers and (x, z) not in fuel else "air")
            if (x, z) in fuel:
                self.set(x + 1, L.FY + 1, z, 'chest[facing=south]{Items:[{Slot:0b,id:"minecraft:bucket",count:1}]}')
                self.set(x + 1, L.FY + 1, z - 1,
                         'oak_wall_sign[facing=north]{front_text:{messages:["[Fuel]","","",""]}}')
            else:
                self.set(x + 1, L.FY + 1, z - 1, "air")
                self.set(x + 1, L.FY + 1, z, "chest[facing=south]")

    def clear_golems(self):
        self.srv.cmd(f"kill {golem_selector()}")
        self.srv.cmd(f"kill {item_selector()}")

    def summon(self, n):
        for x, z in L.spread(L.unit_positions(), n):
            self.srv.run(f"summon minecraft:copper_golem {x + 0.5} {L.FY} {z + 2.5} {{active_effects:[{LUCK}]}}")
        got = count(self.srv.cmd(f"execute if entity {golem_selector()}"))
        if got != n:
            raise RuntimeError(f"summoned {n} golems but {got} are there")

    def build_filler(self):
        self.forceload(L.FAR + 16)
        if "filler" in self.built:
            return
        log("placing 1,849 filler chests out to 128 blocks")
        for x, z in L.filler_positions():
            self.set(x, L.FY + L.FILLER_DY, z, 'chest[facing=south]{Items:[{Slot:0b,id:"minecraft:stone",count:1}]}')
        self.built.add("filler")

    def remove_filler(self):
        if "filler" in self.built:
            for x, z in L.filler_positions():
                self.srv.cmd(f"setblock {x} {L.FY + L.FILLER_DY} {z} minecraft:air")
            self.built.discard("filler")

    def build_cauldrons(self, on):
        if on == ("cauldrons" in self.built):
            return
        self.forceload(L.FAR + 16)
        log(("placing" if on else "removing") + " a cauldron in every chunk out to 128 blocks")
        for x, z in L.chunk_centres(L.FAR):
            self.set(x, L.FY + 2, z, "cauldron" if on else "air")
        (self.built.add if on else self.built.discard)("cauldrons")

    def clear_all(self):
        self.clear_golems()
        self.remove_filler()
        if "cauldrons" in self.built:
            self.build_cauldrons(False)
        x1, z1, x2, z2 = L.unit_bounds()
        self.srv.cmd(f"fill {x1} {L.FY - 1} {z1} {x2} {L.FY + 5} {z2} minecraft:air")
        self.built.discard("units")


def count(out):
    m = re.search(r"count: (\d+)", out, re.I)
    return int(m.group(1)) if m else 0


# ---- settings

class Settings:
    def __init__(self, srv):
        self.srv = srv
        self.base = open(CONFIG).read()

    def apply(self, values):
        import re
        text = self.base
        for k, v in values.items():
            text, n = re.subn(rf"(?m)^{re.escape(k)}: .*$", f"{k}: {v}", text)
            if n != 1:
                raise RuntimeError(f"config.yml has no single '{k}:' line")
        with open(CONFIG, "w") as f:
            f.write(text)
        reply = L.strip_colours(self.srv.cmd("coppergolemclerk reload"))
        if "reloaded" not in reply:
            raise RuntimeError(f"reload failed: {reply}")

    def restore(self):
        with open(CONFIG, "w") as f:
            f.write(self.base)
        self.srv.cmd("coppergolemclerk reload")


def settings_for(point):
    """The settings a point runs with: everything else stays as pluginDev's config.yml has it."""
    return {"disabled-worlds": f"[{WORLD}]" if point["mode"] == "vanilla" else "[]",
            "search-distance": str(point["params"].get("distance", 32)),
            "furnace-feeding": "true"}


# ---- running points

def measure(srv, seconds, every, selector):
    sampler = Sampler(srv, selector)
    t0 = time.time()
    sampler.sample(t0)  # primes the server CPU counter
    samples = []
    while time.time() - t0 < seconds:
        time.sleep(max(0, every - (time.time() - t0) % every))
        samples.append(sampler.sample(t0))
    return samples


def run_point(srv, arena, settings, point, opts):
    test, params = point["test"], point["params"]
    reps = 1 if test in ("soak", "chests") else opts["reps"]
    point["reps"], point["samples"] = [], []
    if test == "chests":
        # The itest perf suite on its own: pluginDev's own settings, none of the perf area loaded.
        arena.clear_golems()
        arena.remove_filler()
        arena.build_cauldrons(False)
        arena.unload()
        settings.restore()
        return run_chests(point)
    settings.apply(settings_for(point))
    for rep in range(reps):
        selector = None
        if test == "baseline":
            arena.clear_golems()
            arena.remove_filler()
            arena.build_cauldrons(False)
            arena.unload()  # the server on its own: none of the perf area loaded
        else:
            far = test in ("search", "cauldrons")
            if not far:
                arena.remove_filler()          # while the far chunks are still loaded
                arena.build_cauldrons(False)
            arena.forceload(L.FAR + 16 if far else UNITS_RADIUS)
            arena.build_units()
            if test == "search":
                arena.build_cauldrons(False)
                arena.build_filler()
            elif test == "cauldrons":
                arena.remove_filler()
                arena.build_cauldrons(params["cauldrons"])
            if test == "cauldrons":
                arena.reset_units(birch=False, hoppers=False, fuel_chests=4)
            else:
                arena.reset_units()
            arena.summon(params.get("golems", 0))
            selector = golem_selector()
        time.sleep(opts["warmup"])
        if test == "soak":
            samples = measure(srv, params["minutes"] * 60, SOAK_SAMPLE_SECONDS, selector)
        else:
            samples = measure(srv, opts["measure"], SAMPLE_SECONDS, selector)
        end = count(srv.cmd(f"execute if entity {selector}")) if selector else None
        summary = L.summarize([s["mspt"] for s in samples])
        point["reps"].append({"rep": rep + 1, "median": summary["median"], "golems_at_end": end})
        point["samples"] += samples
    finish(point)


def finish(point):
    samples = point["samples"]
    point["summary"] = L.summarize([s["mspt"] for s in samples])
    point["summary"].update({
        "server_cpu_pct": L.summarize([s["server_cpu_pct"] for s in samples])["median"],
        "temp_max": max((s["temp_c"] or 0 for s in samples), default=None),
        "mhz_top_low": min((s["mhz_top"] for s in samples if s["mhz_top"]), default=None),
        "holding": L.summarize([s["holding"] for s in samples])["median"],
        "noise": L.noise(samples)})
    if point["test"] == "soak":
        times = [s["t"] for s in samples]
        point["summary"]["mspt_per_hour"] = L.slope_per_hour(times, [s["mspt"] for s in samples])
        point["summary"]["rss_mb_per_hour"] = L.slope_per_hour(times, [s["rss_mb"] for s in samples])


def run_chests(point):
    out = subprocess.run([sys.executable, "-I", os.path.join(ITEST, "run.py"), "many-chests-tick-time", "many-idle-golems-scan"],
                         capture_output=True, text=True).stdout
    point["itest"] = [l for l in out.splitlines() if l.startswith(("PASS", "FAIL", "SKIP"))]
    point["summary"] = {"median": None, "passed": sum(1 for l in point["itest"] if l.startswith("PASS")),
                        "of": len(point["itest"]), "noise": []}


# ---- report

def report(points, meta, compare_with):
    lines = [f"# Performance run {meta['started']}", "",
             f"Plugin {meta['plugin']}, {meta['paper']}. {meta['ref']}. Warm-up {meta['opts']['warmup']} s, "
             f"{meta['opts']['measure']} s measured per point, {meta['opts']['reps']} repeats. CPU governor: {meta.get('governor')}.", ""]
    base = {}
    for p in points:
        if p["test"] == "golems" and p["params"].get("golems") == 0:
            base[("golems", p["mode"])] = p["summary"]["median"]
        if p["test"] == "search" and p["mode"] == "vanilla":
            base[("search", "vanilla")] = p["summary"]["median"]
        if p["test"] == "cauldrons" and not p["params"].get("cauldrons"):
            base[("cauldrons", p["params"].get("distance"))] = p["summary"]["median"]
    lines += ["| Point | ms/tick (median) | p95 | Added | Per golem | Server CPU | Max °C | Top MHz | Holding | Notes |",
              "|---|---|---|---|---|---|---|---|---|---|"]
    for p in points:
        s = p.get("summary", {})
        if p["test"] == "chests":
            lines.append(f"| {L.label(p)} | | | | | | | | | {s.get('passed')}/{s.get('of')} passed: "
                         + "; ".join(l.split('  ')[0] + ' ' + l.split()[2] for l in p.get('itest', [])) + " |")
            continue
        ref = {"golems": base.get(("golems", p["mode"])), "search": base.get(("search", "vanilla")),
               "cauldrons": base.get(("cauldrons", p["params"].get("distance")))}.get(p["test"])
        added = per = ""
        n = p["params"].get("golems") or 0
        if ref is not None and s.get("median") is not None:
            added = f"{s['median'] - ref:+.2f}"
            if p["test"] == "golems" and n:
                per = f"{(s['median'] - ref) / n:.3f}"
        notes = list(s.get("noise", []))
        if p["test"] == "soak":
            notes.append(f"ms/tick {s.get('mspt_per_hour')}/h, memory {s.get('rss_mb_per_hour')} MB/h")
        ends = [r["golems_at_end"] for r in p.get("reps", []) if r.get("golems_at_end") not in (None, n)]
        if ends:
            notes.append(f"golems at the end: {ends} (want {n})")
        lines.append(f"| {L.label(p)} | {s.get('median')} | {s.get('p95')} | {added} | {per} | {s.get('server_cpu_pct')}% | "
                     f"{s.get('temp_max')} | {s.get('mhz_top_low')} | {s.get('holding')} | {'; '.join(notes)} |")
    if compare_with:
        rows = L.compare(compare_with["points"], points)
        lines += ["", f"## Compared with {compare_with['meta']['started']} ({compare_with['meta']['plugin']}, {compare_with['meta']['ref']})", "",
                  "| Point | Before | Now | Change | |", "|---|---|---|---|---|"]
        lines += [f"| {r[0]} | {r[1]} | {r[2]} | {r[3]} | {'**worse**' if r[4] else ''} |" for r in rows]
        worse = sum(1 for r in rows if r[4])
        lines += ["", f"{worse} point(s) worse by more than {L.WORSE_RATIO - 1:.0%} and {L.WORSE_MS} ms." if rows else "No points in common."]
    return "\n".join(lines) + "\n"


def save(out_dir, points, meta, compare_with):
    with open(os.path.join(out_dir, "results.json"), "w") as f:
        json.dump({"meta": meta, "points": points}, f, indent=1)
    with open(os.path.join(out_dir, "summary.md"), "w") as f:
        f.write(report([p for p in points if "summary" in p], meta, compare_with))


# ---- main

def main(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("tests", nargs="+")
    ap.add_argument("--label", default="")
    ap.add_argument("--reps", type=int, default=2)
    ap.add_argument("--warmup", type=int, default=60, help="seconds before sampling each point")
    ap.add_argument("--measure", type=int, default=180, help="seconds sampled per point and repeat")
    ap.add_argument("--counts", default="0,20,50,100,150,200")
    ap.add_argument("--modes", default="plugin,vanilla")
    ap.add_argument("--distances", default="32,64,96,128")
    ap.add_argument("--cauldron-distances", default="32,128")
    ap.add_argument("--search-golems", type=int, default=20)
    ap.add_argument("--soak-golems", type=int, default=100)
    ap.add_argument("--soak-minutes", type=int, default=60)
    ap.add_argument("--compare", help="an earlier results folder to compare with")
    ap.add_argument("--clear", action="store_true", help="remove the whole perf area at the end")
    ap.add_argument("--estimate", action="store_true", help="print the points and how long they'd take, and stop")
    ap.add_argument("--force", action="store_true", help="run even while an itest run is going")
    a = ap.parse_args(argv)
    tests = []
    for t in a.tests:
        tests += ["baseline", "golems", "search", "cauldrons", "chests"] if t == "all" else [t]
    opts = {"reps": a.reps, "warmup": a.warmup, "measure": a.measure,
            "counts": [int(x) for x in a.counts.split(",")], "modes": a.modes.split(","),
            "distances": [int(x) for x in a.distances.split(",")],
            "cauldron_distances": [int(x) for x in a.cauldron_distances.split(",")],
            "search_golems": a.search_golems, "soak_golems": a.soak_golems, "soak_minutes": a.soak_minutes}
    if max(opts["counts"]) > len(L.unit_positions()):
        ap.error(f"at most {len(L.unit_positions())} golems")
    points = L.plan(tests, opts)
    minutes = L.estimate_seconds(points, opts) / 60
    print(f"{len(points)} point(s), about {minutes:.0f} min:")
    for p in points:
        print("   ", L.label(p))
    if a.estimate:
        return 0
    if not a.force:
        busy = subprocess.run(["pgrep", "-f", "tools/itest/run.py"], capture_output=True, text=True).stdout.strip()
        if busy:
            print("An itest run is going on pluginDev (pid " + busy.replace("\n", ", ") + "): wait for it, or use --force.")
            return 1
    compare_with = None
    if a.compare:
        with open(os.path.join(a.compare, "results.json")) as f:
            compare_with = json.load(f)

    srv = Server()
    players = L.parse_players(srv.cmd("list")) or 0
    if players:
        print(f"{players} player(s) on pluginDev: they'd skew the numbers. Ask them to leave, or use --force.")
        if not a.force:
            return 1
    started = datetime.datetime.now()
    name = started.strftime("%Y%m%d-%H%M%S") + (f"-{a.label}" if a.label else "")
    out_dir = os.path.join(RESULTS, name)
    os.makedirs(out_dir, exist_ok=True)
    latest = os.path.join(RESULTS, "latest")
    if os.path.islink(latest):
        os.remove(latest)
    os.symlink(out_dir, latest)
    meta = {"started": started.isoformat(timespec="seconds"), "label": a.label, "opts": opts,
            "plugin": L.strip_colours(srv.cmd("version CopperGolemClerk")).strip().splitlines()[0][:120],
            "paper": L.strip_colours(srv.cmd("version")).strip().splitlines()[0][:120],
            "ref": os.environ.get("PERF_REF", "jar as deployed"),
            "governor": os.environ.get("PERF_GOVERNOR") or open("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor").read().strip(), "host_throttles_before": throttles()}
    log(f"results: {out_dir}")
    settings = Settings(srv)
    arena = Arena(srv)
    done = []
    try:
        for i, point in enumerate(points):
            log(f"{i + 1}/{len(points)} {L.label(point)}")
            run_point(srv, arena, settings, point, opts)
            done.append(point)
            s = point["summary"]
            log(f"    median {s.get('median')} ms/tick, p95 {s.get('p95')}, max {s.get('temp_max')}°C"
                + (f"; NOISY: {'; '.join(s['noise'])}" if s.get("noise") else ""))
            meta["host_throttles_after"] = throttles()
            save(out_dir, done, meta, compare_with)
    finally:
        log("cleaning up: golems, config.yml, force-loaded chunks" + (", the perf area" if a.clear else ""))
        try:
            arena.clear_golems()
            arena.remove_filler()
            if "cauldrons" in arena.built:
                arena.build_cauldrons(False)
            if a.clear:
                arena.built |= {"filler", "cauldrons"}  # including any left by an interrupted run
                arena.forceload(L.FAR + 16)
                arena.clear_all()
        finally:
            settings.restore()
            arena.unload()
            meta["finished"] = datetime.datetime.now().isoformat(timespec="seconds")
            save(out_dir, done, meta, compare_with)
    log(f"done: {out_dir}/summary.md")
    print(open(os.path.join(out_dir, "summary.md")).read())
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
