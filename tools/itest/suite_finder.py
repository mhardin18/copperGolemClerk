"""finder: /cgc golems lists copper golems, loaded or not (where they were last seen).

From the console, /cgc golems <x> <y> <z> lists the 10 golems nearest that spot; each
scenario asks about its own platform, names its golems ("itest-<scenario>-...") and looks
for its own lines.
"""
import os
import re

from arena import SERVER, Trigger, _count, scenario
from suite_modes import LUCK, VANILLA

GOLEMS_FILE = SERVER + "/plugins/CopperGolemClerk/golems.yml"


def named(c, dx, dz, name, extra=""):
    x, y, z = c.abs(dx, dz)
    c.run(f'summon minecraft:copper_golem {x + 0.5} {y} {z + 0.5} '
          f'{{CustomName:"{name}",active_effects:[{LUCK}]{extra}}}')
    return x, y, z


def golems_reply(c):
    x, y, z = c.abs(10, 10)
    return re.sub(r"§.", "", c.r.cmd(f"cgc golems {x} {y} {z}"))


def line_of(reply, name):
    for line in reply.splitlines() or [reply]:
        if f'"{name}"' in line:
            return line
    # RCON may join lines; cut at the next numbered entry
    m = re.search(rf'"{re.escape(name)}"(.*?)(?=\s\d+\. |$)', reply)
    return m.group(0) if m else None


@scenario("golems-lists-loaded", "finder", 30, "/cgc golems lists a loaded golem with its position, what it carries, its copper, and a vanilla-mode golem as such.")
def _(c):
    x, y, z = named(c, 6, 10, "itest-fl-carrier", ',equipment:{mainhand:{id:"minecraft:birch_log",count:5}},NoAI:1b')
    named(c, 12, 10, "itest-fl-vanilla", "," + VANILLA + ",NoAI:1b")

    def check():
        reply = golems_reply(c)
        a = line_of(reply, "itest-fl-carrier") or ""
        b = line_of(reply, "itest-fl-vanilla") or ""
        return [(f"{x} {y} {z}" in a, f"lists the carrier at {x} {y} {z}: {a!r}"),
                ("carrying 5 birch log" in a, "says it carries 5 birch logs"),
                ("unaffected" in a, "says its copper is unaffected"),
                ("vanilla mode" in b, f"the vanilla golem says vanilla mode: {b!r}"),
                ("vanilla mode" not in a, "the plugin golem doesn't")]
    return check


@scenario("golems-near-a-spot", "finder", 30, "/cgc golems <x> <y> <z> from the console: the golems nearest that spot, nearest first, with distance and direction.")
def _(c):
    named(c, 12, 10, "itest-fn-near", ",NoAI:1b")
    named(c, 18, 10, "itest-fn-far", ",NoAI:1b")

    def check():
        reply = golems_reply(c)
        near, far = reply.find("itest-fn-near"), reply.find("itest-fn-far")
        line = line_of(reply, "itest-fn-near") or ""
        return [(near >= 0 and far > near, "lists both, the nearer first"),
                ("(2 blocks E)" in line, f"says it's 2 blocks east: {line!r}"),
                ("near " in reply.splitlines()[0] if reply else False, "the heading names the spot")]
    return check


@scenario("golems-unloaded-last-seen", "finder", 120, "A golem whose chunk unloads is still listed, where it was last seen; it's live again once the chunk reloads.")
def _(c):
    x, y, z = named(c, 6, 10, "itest-fu-sleeper", ",NoAI:1b")
    unload, reload = Trigger(), Trigger()
    saw = {}

    def mine():
        return _count(c.r.cmd(f'execute if entity @e[type=copper_golem,name="itest-fu-sleeper"]'))

    def check():
        listed = line_of(golems_reply(c), "itest-fu-sleeper") or ""
        if not saw.get("loaded") and listed and "not loaded" not in listed:
            saw["loaded"] = True
        unload.fire_when(lambda: saw.get("loaded") and c.elapsed() > 5, lambda: c.forceload(False))
        if unload.at is None:
            return [(False, "waiting to see it listed while loaded")]
        if not saw.get("unloaded"):
            if mine() == 0 and "not loaded" in listed:
                saw["unloaded"] = listed
            return [(False, f"waiting for the chunk to unload and the golem to be listed as not loaded: {listed!r}")]
        reload.fire_when(lambda: True, lambda: c.forceload(True))
        back = mine() == 1 and "not loaded" not in listed
        return [(f"{x} {y} {z}" in saw["unloaded"], f"listed at its last spot {x} {y} {z}: {saw['unloaded']!r}"),
                ("last seen" in saw["unloaded"], "says when it was last seen"),
                (back, "listed as loaded again after reloading")]
    return check


@scenario("golems-killed-forgotten", "finder", 60, "A golem that's killed is no longer listed.")
def _(c):
    named(c, 6, 10, "itest-fk-doomed", ",NoAI:1b")
    killed = Trigger()

    def check():
        listed = line_of(golems_reply(c), "itest-fk-doomed")
        killed.fire_when(lambda: listed is not None,
                         lambda: c.r.cmd('kill @e[type=copper_golem,name="itest-fk-doomed"]'))
        if killed.since() < 2:
            return [(False, "waiting to see it listed, then killing it")]
        return [(listed is None, f"no longer listed (got {listed!r})")]
    return check


# Away from the platforms, so its chunk can be unloaded without touching them.
UNLOAD_SPOT = (-4000, 150, -4000)


@scenario("golems-killed-then-unloaded", "finder", 60, "A golem killed just before its chunk unloads (still in its death animation) is forgotten, not listed as 'not loaded'.", alone=True)
def _(c):
    x, y, z = UNLOAD_SPOT
    c.r.cmd(f"forceload add {x} {z}")
    c.r.cmd(f"setblock {x} {y - 1} {z} minecraft:stone")
    c.r.cmd(f'summon minecraft:copper_golem {x + 0.5} {y} {z + 0.5} {{CustomName:"itest-ku-doomed",NoAI:1b}}')
    t = Trigger()

    def reply():
        return re.sub(r"§.", "", c.r.cmd(f"cgc golems {x} {y} {z}"))

    def kill_and_unload():
        c.r.cmd('kill @e[type=copper_golem,name="itest-ku-doomed"]')
        c.r.cmd(f"forceload remove {x} {z}")  # in the same tick, during the death animation

    def check():
        listed = line_of(reply(), "itest-ku-doomed")
        t.fire_when(lambda: listed is not None, kill_and_unload)
        if t.since() < 5:
            return [(False, "waiting to see it listed, then killing it and unloading its chunk")]
        return [(listed is None, f"no longer listed (got {listed!r})")]
    return check


@scenario("golems-saved-to-file", "finder", 75, "Golem positions are saved to golems.yml (every 30 seconds), so they survive a restart.")
def _(c):
    named(c, 6, 10, "itest-fs-saved", ",NoAI:1b")

    def check():
        text = open(GOLEMS_FILE).read() if os.path.isfile(GOLEMS_FILE) else ""
        return [("itest-fs-saved" in text, "golems.yml has the golem")]
    return check


@scenario("golems-bad-position", "finder", 30, "/cgc golems with half a position or a bad world says what's wrong.")
def _(c):
    def say(cmd):
        return re.sub(r"§.", "", c.r.cmd(cmd))
    return lambda: [("Give a whole position" in say("cgc golems 10 64"), "half a position is reported"),
                    ("whole-number" in say("cgc golems a b c"), "letters are reported"),
                    ("No world named 'nowhere'" in say("cgc golems 0 64 0 nowhere"), "a bad world is reported")]
