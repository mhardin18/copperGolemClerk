"""Test platforms and the scenario registry for the in-game tests."""
import bisect
import os
import re
import time

SERVER = "/opt/minecraft/pluginDev"
CONFIG = SERVER + "/plugins/CopperGolemClerk/config.yml"
PLAYER_ALIASES = SERVER + "/plugins/CopperGolemClerk/player-aliases.yml"
X0, Y, Z0, SPACING, SIZE = -500, 150, 0, 80, 21

# Paper logs this whenever an item frame is summoned by command; the frame is fine.
KNOWN_HARMLESS = ("Block-attached entity at invalid position",)

SCENARIOS = []


class Skip(Exception):
    """Raised by a scenario's setup when something it needs isn't available."""


def scenario(name, suite, timeout, description, alone=False, manual=False, allow_errors=(), dimension=None, place=None):
    """Register a scenario.

    alone: changes something server-wide (config, reload, tick timing), so it runs by
           itself after the parallel ones.
    manual: needs a person (a server restart); runs only when named explicitly.
    allow_errors: server log errors this scenario expects (e.g. a bad config).
    dimension: build the platform in another dimension ("minecraft:the_nether", "minecraft:the_end").
    place: (x, z) for the platform's corner instead of the usual spot (e.g. inside a test claim);
           scenarios sharing a place must be alone=True.
    The function gets a Cell, builds its setup, and returns check() -> [(ok, text)].
    """
    def wrap(fn):
        SCENARIOS.append(dict(name=name, suite=suite, timeout=timeout, description=description,
                              alone=alone, manual=manual, allow_errors=allow_errors, dimension=dimension, place=place,
                              build=fn))
        return fn
    return wrap


LUCK = '{id:"minecraft:luck",duration:-1,show_particles:0b,show_icon:0b}'
# Scenarios that break, empty or kill something while a golem walks use a long walk (across
# the platform, about 20 blocks) at normal speed, so a check every 3 seconds catches it.
# Slowed golems (movement_speed 0.07) were tried and dropped in 3.1.0: they barely moved,
# gave up short walks, and ran past their scenario's time limit.


class Log:
    """Reads the server log from a starting offset onwards.

    Every scenario in a batch reads the log on every poll, so lines are read from the file
    once and kept: a read only fetches what was written since the last one. Re-reading the
    whole file each time made a poll round of a full run take most of a minute."""

    def __init__(self, path):
        self.path = path
        self._lines, self._offsets, self._read_to = [], [], 0

    def size(self):
        try:
            return os.path.getsize(self.path)
        except OSError:
            return 0

    def _refresh(self):
        size = self.size()
        if size < self._read_to:  # a new log file (server restarted): start over
            self._lines, self._offsets, self._read_to = [], [], 0
        if size == self._read_to:
            return
        try:
            with open(self.path, "rb") as f:
                f.seek(self._read_to)
                data = f.read(size - self._read_to)
        except OSError:
            return
        end = data.rfind(b"\n")
        if end < 0:
            return  # no complete line yet
        at = self._read_to
        for raw in data[:end].split(b"\n"):
            self._offsets.append(at)
            self._lines.append(raw.decode("utf-8", errors="replace").rstrip("\r"))
            at += len(raw) + 1
        self._read_to += end + 1

    def since(self, offset):
        """Complete lines that start at or after this byte offset (from size())."""
        self._refresh()
        return self._lines[bisect.bisect_left(self._offsets, offset):]


_COORDS = {}  # log line -> the x, z pairs in it (each line is parsed once, not once per scenario)


def _coords(line):
    found = _COORDS.get(line)
    if found is None:
        found = _COORDS[line] = [(int(m.group(1)), int(m.group(3)))
                                 for m in re.finditer(r"(-?\d+),\s*(-?\d+),\s*(-?\d+)", line)]
    return found


class InDimension:
    """Runs every command in another dimension (execute in <dimension> run ...)."""

    def __init__(self, rcon, dimension):
        self.rcon, self.dimension = rcon, dimension

    def cmd(self, command):
        if command.startswith("coppergolemclerk") or command.startswith("cgc"):
            return self.rcon.cmd(command)  # plugin commands: the world is in the arguments
        return self.rcon.cmd(f"execute in {self.dimension} run {command}")


class Cell:
    """One scenario's platform. Coordinates are relative: (dx, dz) on the floor."""

    def __init__(self, rcon, index, log, dimension=None, place=None):
        self.dimension = dimension
        self.r = InDimension(rcon, dimension) if dimension else rcon
        self.log = log
        self.x, self.z = place if place else (X0, Z0 + index * SPACING)
        self.chests = {}
        self.cleanup = []  # extra commands for clear(): blocks a scenario put outside its platform
        self.started = time.time()
        self.log_start = log.size()

    # ---- basics
    def abs(self, dx, dz, dy=0):
        return self.x + dx, Y + dy, self.z + dz

    def run(self, command):
        out = self.r.cmd(command)
        bad = ("Unknown", "Incorrect", "Expected", "Invalid", "not loaded", "Could not", "Failed", "Only one")
        if any(b in out for b in bad):
            raise RuntimeError(f"command failed: {command}\n  -> {out}")
        return out

    def elapsed(self):
        return time.time() - self.started

    def log_lines(self):
        return self.log.since(self.log_start)

    def cell_lines(self):
        """log_lines() about this platform (a coordinate on it). Kept between calls, so each
        call only looks at the lines written since the last one."""
        lines = self.log_lines()
        if getattr(self, "_cell_from", None) != self.log_start or len(lines) < self._cell_scanned:
            self._cell_from, self._cell_scanned, self._cell = self.log_start, 0, []
        self._cell.extend(l for l in lines[self._cell_scanned:] if self.in_cell(l))
        self._cell_scanned = len(lines)
        return list(self._cell)

    def in_cell(self, line):
        for x, z in _coords(line):
            if self.x - 2 <= x <= self.x + SIZE + 1 and self.z - 2 <= z <= self.z + SIZE + 1:
                return True
        return False

    def chunks(self):
        # Extra room east of the platform for scenarios that place chests out of golem range.
        return (self.x - 2, self.z - 2, self.x + SIZE + 36, self.z + SIZE + 2)

    def forceload(self, on=True):
        self.r.cmd("forceload {} {} {} {} {}".format("add" if on else "remove", *self.chunks()))

    def selector(self, kind, pad=4):
        x, z, s = self.x, self.z, SIZE
        return f"@e[type=minecraft:{kind},x={x - pad},y=-64,z={z - pad},dx={s + 2 * pad},dy=400,dz={s + 2 * pad}]"

    # ---- arena
    def build_floor(self):
        x, z, s = self.x, self.z, SIZE
        self.run(f"fill {x - 1} {Y - 3} {z - 1} {x + s} {Y + 8} {z + s} minecraft:air")
        self.run(f"fill {x - 1} {Y - 1} {z - 1} {x + s} {Y - 1} {z + s} minecraft:glowstone")  # lit: no mob spawns
        for wall in (f"{x - 1} {Y} {z - 1} {x + s} {Y + 2} {z - 1}", f"{x - 1} {Y} {z + s} {x + s} {Y + 2} {z + s}",
                     f"{x - 1} {Y} {z - 1} {x - 1} {Y + 2} {z + s}", f"{x + s} {Y} {z - 1} {x + s} {Y + 2} {z + s}"):
            self.run(f"fill {wall} minecraft:glass")

    def clear(self):
        x, z, s = self.x, self.z, SIZE
        for command in self.cleanup:
            self.r.cmd(command)
        for name, blocks in self.chests.items():  # including any placed off the platform
            for bx, by, bz in blocks:
                self.r.cmd(f"setblock {bx} {by} {bz} minecraft:air")
                self.r.cmd(f"setblock {bx} {by - 1} {bz} minecraft:air")
        for kind in ("copper_golem", "item_frame", "glow_item_frame", "item", "oak_boat", "cat", "leash_knot", "item_display", "iron_golem", "snow_golem"):
            self.r.cmd(f"kill {self.selector(kind)}")
        self.r.cmd(f"fill {x - 1} {Y - 3} {z - 1} {x + s} {Y + 8} {z + s} minecraft:air")

    # ---- building blocks
    def chest(self, name, dx, dz, items=(), block="chest", half=None, dy=0, facing="south"):
        """A container. half='left'/'right' for one half of a double chest."""
        props = [f"facing={facing}"] + ([f"type={half}"] if half else [])
        state = "[" + ",".join(props) + "]" if block != "barrel" else "[facing=up]"
        x, y, z = self.abs(dx, dz, dy)
        # Recorded first, so clear() removes it even if setup fails; and the spot is emptied
        # first (unchecked), since setblock refuses to place a block that is already there,
        # as one left off the platform by an interrupted run would be.
        self.chests.setdefault(name, []).append((x, y, z))
        self.r.cmd(f"setblock {x} {y} {z} minecraft:air")
        self.run(f"setblock {x} {y} {z} minecraft:{block}{state}")
        for slot, (item, count) in enumerate(items):
            self.run(f"item replace block {x} {y} {z} container.{slot} with {qualify(item)} {count}")

    def double_chest(self, name, dx, dz, items=()):
        """Left half at dx, right half at dx-1 (vanilla: a south-facing left half joins to the west)."""
        self.chest(name, dx, dz, items, half="left")
        self.chest(name, dx - 1, dz, (), half="right")

    def sign(self, dx, dz, text, dy=0):
        """Wall sign on the north face of the chest at (dx, dz). text: one line, or a list of up to 4."""
        lines = ([text] if isinstance(text, str) else list(text)) + [""] * 4
        messages = ",".join(f'"{line}"' for line in lines[:4])
        x, y, z = self.abs(dx, dz - 1, dy)
        self.run(f'setblock {x} {y} {z} minecraft:oak_wall_sign[facing=north]{{front_text:{{messages:[{messages}]}}}}')

    def remove_sign(self, dx, dz, dy=0):
        x, y, z = self.abs(dx, dz - 1, dy)
        self.run(f"setblock {x} {y} {z} minecraft:air")

    def frame(self, dx, dz, item):
        """Item frame on the west face of the chest at (dx, dz), facing west."""
        x, y, z = self.abs(dx - 1, dz)
        self.run(f'summon minecraft:item_frame {x} {y} {z} {{Facing:4b,Item:{{id:"minecraft:{item}",count:1}}}}')

    def block(self, dx, dz, block, dy=0):
        x, y, z = self.abs(dx, dz, dy)
        self.run(f"setblock {x} {y} {z} minecraft:{block}")

    def remove(self, dx, dz, dy=0):
        """Remove a block outright (a chest's contents vanish with it)."""
        self.block(dx, dz, "air", dy)

    def golem(self, dx, dz, awake=True):
        """Summon a copper golem. awake=True gives it an invisible, endless Luck effect:
        Paper keeps mobs with potion effects fully active, which is how golems behave with
        a player nearby. awake=False leaves it to Paper's entity activation: with no player
        within 32 blocks, a golem standing still only thinks in short bursts once a minute."""
        x, y, z = self.abs(dx, dz)
        parts = [f"active_effects:[{LUCK}]"] if awake else []
        nbt = " {" + ",".join(parts) + "}" if parts else ""
        self.run(f"summon minecraft:copper_golem {x + 0.5} {y} {z + 0.5}{nbt}")

    # ---- reads
    def count(self, name, item="*"):
        """How many of an item (or anything, '*') are in a named chest (all its blocks)."""
        total = 0
        for x, y, z in self.chests[name]:
            total += _count(self.r.cmd(f"execute if items block {x} {y} {z} container.* {qualify(item)}"))
        return total

    def holding(self, item="*"):
        """Items held by all copper golems on this platform."""
        return _count(self.r.cmd(f"execute if items entity {self.selector('copper_golem')} weapon.mainhand {qualify(item)}"))

    def dropped(self, item="*"):
        """Items lying on the ground on (or under) this platform."""
        return _count(self.r.cmd(f"execute if items entity {self.selector('item')} contents {qualify(item)}"))

    def total(self, item):
        """Every copy of an item: in the named chests, golems' hands and on the ground."""
        return sum(self.count(n, item) for n in self.chests) + self.holding(item) + self.dropped(item)

    def golems(self):
        return _count(self.r.cmd(f"execute if entity {self.selector('copper_golem')}"))


def qualify(item):
    return item if item == "*" or ":" in item.split("[")[0] else f"minecraft:{item}"


def _count(out):
    m = re.search(r"count: (\d+)", out, re.I)
    return int(m.group(1)) if m else 0


def expect(cell, chest, item, want):
    got = cell.count(chest, item)
    return got == want, f"{chest} has {got} {item.split('[')[0]} (want {want})"


def conserved(cell, item, want):
    got = cell.total(item)
    return got == want, f"{got} {item} exist in chests, hands and on the ground (want {want}: none lost or duplicated)"


class Trigger:
    """Fire an action once, the first time a condition is true; remember when."""

    def __init__(self):
        self.at = None
        self.lines = 0

    def fire_when(self, condition, action, lines=None):
        """Run action once, the first time condition() holds. lines: a callable giving the
        cell's log lines; their count when it fired is kept in self.lines."""
        if self.at is None and condition():
            if lines is not None:
                self.lines = len(lines())
            action()
            self.at = time.time()
        return self.at is not None

    def since(self):
        return 0 if self.at is None else time.time() - self.at
