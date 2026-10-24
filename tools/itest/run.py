#!/usr/bin/env python3
"""In-game scenario tests for SmartCopperGolems, run against pluginDev over RCON.

Each scenario gets its own walled, lit platform high in the sky at x=-500,
80 blocks apart along z (golems search 32 blocks, so cells can't interfere).
Chunks are force-loaded, so golems work with no player nearby. A scenario
places chests, signs, frames and items, summons one copper golem, then the
runner polls chest contents until every check passes or the time runs out.
Everything is removed afterwards (use --keep to inspect a failed run).

    python3 -I tools/itest/run.py              # all scenarios
    python3 -I tools/itest/run.py fetch return # only these (substring match)
    python3 -I tools/itest/run.py --keep       # leave the arena standing
    python3 -I tools/itest/run.py --list
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rcon import from_properties  # noqa: E402

SERVER = "/opt/minecraft/pluginDev"
X0, Y, Z0, SPACING, SIZE = -500, 150, 0, 80, 21
POLL = 3

# ---------------------------------------------------------------- arena helpers

class Cell:
    """One scenario's platform. Coordinates are relative: (dx, dz) on the floor."""

    def __init__(self, rcon, index):
        self.r = rcon
        self.x, self.z = X0, Z0 + index * SPACING
        self.chests = {}

    def abs(self, dx, dz, dy=0):
        return self.x + dx, Y + dy, self.z + dz

    def run(self, command):
        out = self.r.cmd(command)
        bad = ("Unknown", "Incorrect", "Expected", "Invalid", "not loaded", "Could not", "Failed")
        if any(b in out for b in bad):
            raise RuntimeError(f"command failed: {command}\n  -> {out}")
        return out

    def chunks(self):
        return (self.x - 2, self.z - 2, self.x + SIZE + 2, self.z + SIZE + 2)

    def build_floor(self):
        x, z, s = self.x, self.z, SIZE
        self.run(f"fill {x - 1} {Y - 1} {z - 1} {x + s} {Y + 5} {z + s} minecraft:air")
        self.run(f"fill {x - 1} {Y - 1} {z - 1} {x + s} {Y - 1} {z + s} minecraft:glowstone")  # lit: no mob spawns
        for wall in (f"{x - 1} {Y} {z - 1} {x + s} {Y + 2} {z - 1}", f"{x - 1} {Y} {z + s} {x + s} {Y + 2} {z + s}",
                     f"{x - 1} {Y} {z - 1} {x - 1} {Y + 2} {z + s}", f"{x + s} {Y} {z - 1} {x + s} {Y + 2} {z + s}"):
            self.run(f"fill {wall} minecraft:glass")

    def clear(self):
        x, z, s = self.x, self.z, SIZE
        sel = f"x={x - 4},y=-64,z={z - 4},dx={s + 8},dy=400,dz={s + 8}"
        for t in ("copper_golem", "item_frame", "glow_item_frame", "item"):
            self.r.cmd(f"kill @e[type=minecraft:{t},{sel}]")
        self.r.cmd(f"fill {x - 1} {Y - 1} {z - 1} {x + s} {Y + 5} {z + s} minecraft:air")

    def chest(self, name, dx, dz, items=(), block="chest", half=None):
        """A chest facing south. half='left'/'right' for one half of a double chest."""
        state = f"[facing=south,type={half}]" if half else "[facing=south]"
        x, y, z = self.abs(dx, dz)
        self.run(f"setblock {x} {y} {z} minecraft:{block}{state}")
        for slot, (item, count) in enumerate(items):
            self.run(f"item replace block {x} {y} {z} container.{slot} with minecraft:{item} {count}")
        self.chests.setdefault(name, []).append((x, y, z))

    def double_chest(self, name, dx, dz, items=()):
        """Left half at dx, right half at dx-1 (vanilla: a south-facing left half joins to the west)."""
        self.chest(name, dx, dz, items, half="left")
        self.chest(name, dx - 1, dz, (), half="right")

    def sign(self, dx, dz, text):
        """Wall sign on the north face of the chest at (dx, dz)."""
        x, y, z = self.abs(dx, dz - 1)
        self.run(f'setblock {x} {y} {z} minecraft:oak_wall_sign[facing=north]{{front_text:{{messages:["{text}","","",""]}}}}')

    def frame(self, dx, dz, item):
        """Item frame on the west face of the chest at (dx, dz), facing west."""
        x, y, z = self.abs(dx - 1, dz)
        self.run(f'summon minecraft:item_frame {x} {y} {z} {{Facing:4b,Item:{{id:"minecraft:{item}",count:1}}}}')

    def golem(self, dx, dz):
        x, y, z = self.abs(dx, dz)
        self.run(f"summon minecraft:copper_golem {x + 0.5} {y} {z + 0.5}")

    # ---- reads
    def count(self, name, item="*"):
        """How many of an item (or anything, '*') are in a named chest (both halves)."""
        total = 0
        what = item if item == "*" else f"minecraft:{item}"
        for x, y, z in self.chests[name]:
            out = self.r.cmd(f"execute if items block {x} {y} {z} container.* {what}")
            m = re.search(r"count: (\d+)", out, re.I)
            total += int(m.group(1)) if m else 0
        return total

    def golem_holding(self):
        x, z, s = self.x, self.z, SIZE
        out = self.r.cmd(f"execute if items entity @e[type=minecraft:copper_golem,x={x},y={Y - 2},z={z},"
                         f"dx={s},dy=8,dz={s},limit=1] weapon.mainhand *")
        m = re.search(r"count: (\d+)", out, re.I)
        return int(m.group(1)) if m else 0

# ---------------------------------------------------------------- scenarios
# Each: (name, description, timeout seconds, build(cell), check(cell) -> list of (ok, text))
# Layout: copper chest at (2,10), golem at (4,10); other chests further along +x.

def expect(cell, chest, item, want):
    got = cell.count(chest, item)
    return got == want, f"{chest} has {got} {item} (want {want})"

S = []

def scenario(name, timeout, description):
    def wrap(fn):
        S.append((name, description, timeout, fn))
        return fn
    return wrap

@scenario("group-match", 150, "Birch logs go to the chest holding an oak log, past a nearer empty chest.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("logs", 14, 10, [("oak_log", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "logs", "birch_log", 16), expect(c, "empty", "birch_log", 0)]

@scenario("match-mode-all", 120, "A log chest with a stray dirt block is not a group match; birch logs use the empty chest.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("mixed", 8, 10, [("oak_log", 1), ("dirt", 1)])
    c.chest("empty", 14, 10)
    c.golem(4, 10)
    return lambda: [expect(c, "empty", "birch_log", 16), expect(c, "mixed", "birch_log", 0)]

@scenario("frame-label", 200, "A framed stone pickaxe takes only stone pickaxes; the iron one goes to the tools chest.")
def _(c):
    c.chest("copper", 2, 10, [("stone_pickaxe", 1), ("iron_pickaxe", 1)], block="waxed_copper_chest")
    c.chest("labelled", 9, 10)
    c.frame(9, 10, "stone_pickaxe")
    c.chest("tools", 14, 10, [("wooden_axe", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "labelled", "stone_pickaxe", 1), expect(c, "labelled", "iron_pickaxe", 0),
                    expect(c, "tools", "iron_pickaxe", 1)]

@scenario("sign-plural", 240, "A [Rail] sign means the rails group: powered rails and a minecart go there.")
def _(c):
    c.chest("copper", 2, 10, [("powered_rail", 8), ("minecart", 1)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("rails", 14, 10)
    c.sign(14, 10, "[Rail]")
    c.golem(4, 10)
    return lambda: [expect(c, "rails", "powered_rail", 8), expect(c, "rails", "minecart", 1), expect(c, "empty", "*", 0)]

@scenario("most-specific", 270, "Stews go to [stew] (word match, 3 items) rather than [food]; bread goes to [food].")
def _(c):
    c.chest("copper", 2, 10, [("mushroom_stew", 1), ("mushroom_stew", 1), ("bread", 8)], block="waxed_copper_chest")
    c.chest("food", 8, 10)
    c.sign(8, 10, "[food]")
    c.chest("stew", 14, 10)
    c.sign(14, 10, "[stew]")
    c.golem(4, 10)
    return lambda: [expect(c, "stew", "mushroom_stew", 2), expect(c, "food", "mushroom_stew", 0), expect(c, "food", "bread", 8)]

@scenario("far-half-sign", 120, "A sign on the far half of a double chest still labels the whole chest.")
def _(c):
    c.chest("copper", 2, 10, [("coal_ore", 8)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.double_chest("ores", 15, 10)
    c.sign(15, 10, "[ore]")  # on the left half, the one further from the golem
    c.golem(4, 10)
    return lambda: [expect(c, "ores", "coal_ore", 8), expect(c, "empty", "*", 0)]

@scenario("overflow-before-empty", 120, "An item with no matching chest goes to [Overflow], not the nearer empty chest.")
def _(c):
    c.chest("copper", 2, 10, [("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("empty", 6, 10)
    c.chest("overflow", 14, 10)
    c.sign(14, 10, "[Overflow]")
    c.golem(4, 10)
    return lambda: [expect(c, "overflow", "sculk_shrieker", 1), expect(c, "empty", "*", 0)]

@scenario("full-chest-overflow", 120, "When the matching log chest is full, birch logs go to [Overflow].")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("full", 8, 10, [("oak_log", 64)] * 27)
    c.chest("overflow", 14, 10)
    c.sign(14, 10, "[Overflow]")
    c.golem(4, 10)
    return lambda: [expect(c, "overflow", "birch_log", 16), expect(c, "full", "birch_log", 0)]

@scenario("return-homeless", 200, "With nowhere to put it, the item goes back to its copper chest after ~90s and stays there.")
def _(c):
    c.chest("copper", 2, 10, [("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("busy", 14, 10, [("dirt", 64)] * 27)  # a full, unrelated chest: no home, no empty chest
    c.golem(4, 10)
    picked_up = []  # must leave the copper chest first, or the check would pass at the start

    def check():
        in_chest, holding = c.count("copper", "sculk_shrieker"), c.golem_holding()
        if in_chest == 0 and holding:
            picked_up.append(True)
        return [(bool(picked_up), "golem picked the item up" if picked_up else "golem hasn't picked the item up yet"),
                (in_chest == 1, f"copper chest has {in_chest} sculk_shrieker (want 1, returned)"),
                (holding == 0, f"golem holding {holding} items (want 0)")]
    return check

@scenario("fetch-from-overflow", 150, "An idle golem fetches granite from [Overflow] into the stone chest.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")  # empty: nothing else to do
    c.chest("overflow", 8, 10, [("granite", 16)])
    c.sign(8, 10, "[Overflow]")
    c.chest("stone", 14, 10, [("andesite", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "stone", "granite", 16), expect(c, "overflow", "granite", 0)]

# ---------------------------------------------------------------- runner

def main(argv):
    keep = "--keep" in argv
    names = [a for a in argv if not a.startswith("--")]
    if "--list" in argv:
        for i, (n, d, t, _) in enumerate(S):
            print(f"{n:24} {t:>4}s  {d}")
        return 0
    chosen = [(i, s) for i, s in enumerate(S) if not names or any(n in s[0] for n in names)]
    if not chosen:
        print("no scenarios match", names)
        return 2

    r = from_properties(os.path.join(SERVER, "server.properties"))
    log_path = os.path.join(SERVER, "logs/latest.log")
    log_start = os.path.getsize(log_path)
    cells = {i: Cell(r, i) for i, _ in chosen}

    # Start clean: reload clears "no home" marks left on copper chests by earlier runs.
    r.cmd("smartgolems reload")
    print(f"Building {len(chosen)} scenario(s) at x={X0}, y={Y} ...")
    for i, c in cells.items():
        r.cmd("forceload add {} {} {} {}".format(*c.chunks()))
    time.sleep(2)
    checks, started, done = {}, {}, {}
    last_left = None
    try:
        for i, (name, desc, timeout, build) in chosen:
            c = cells[i]
            c.clear()
            c.build_floor()
            checks[i] = build(c)
            started[i] = time.time()
        while len(done) < len(chosen):
            time.sleep(POLL)
            for i, (name, desc, timeout, _) in chosen:
                if i in done:
                    continue
                results = checks[i]()
                elapsed = time.time() - started[i]
                if all(ok for ok, _ in results):
                    done[i] = ("PASS", elapsed, results)
                elif elapsed > timeout:
                    done[i] = ("FAIL", elapsed, results)
            left = [S[i][0] for i, _ in chosen if i not in done]
            if left and len(left) != last_left:
                print(f"  {len(chosen) - len(left)}/{len(chosen)} finished; waiting on {', '.join(left)}", flush=True)
                last_left = len(left)
    finally:
        if not keep:
            for c in cells.values():
                c.clear()
            for c in cells.values():
                r.cmd("forceload remove {} {} {} {}".format(*c.chunks()))

    log = open(log_path, errors="replace").read()[log_start:] if os.path.exists(log_path) else ""
    failed = 0
    for i, (name, desc, timeout, _) in chosen:
        status, elapsed, results = done[i]
        print(f"{status}  {name:24} {elapsed:5.0f}s  {desc}")
        if status == "FAIL":
            failed += 1
            for ok, text in results:
                print(f"        {'ok ' if ok else 'BAD'} {text}")
            c = cells[i]
            def in_cell(line):
                for m in re.finditer(r"(-?\d+),\s*(-?\d+),\s*(-?\d+)", line):
                    x, z = int(m.group(1)), int(m.group(3))
                    if c.x - 2 <= x <= c.x + SIZE + 1 and c.z - 2 <= z <= c.z + SIZE + 1:
                        return True
                return False
            near = [l for l in log.splitlines() if "SmartCopperGolems" in l and in_cell(l)]
            for l in near[-8:]:
                print("        log:", l[l.find("]: ") + 3:][:160])
    print(f"\n{len(chosen) - failed}/{len(chosen)} passed" + ("  (arena kept)" if keep else ""))
    # Paper logs this whenever an item frame is summoned by command; the frame is fine.
    known = ("Block-attached entity at invalid position",)
    errors = [l for l in log.splitlines()
              if ("ERROR" in l or "Exception" in l) and not any(k in l for k in known)]
    if errors:
        print(f"{len(errors)} error line(s) in the server log during the run, e.g.: {errors[0][:160]}")
    return 1 if failed else 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
