"""finder: /cgc golems lists copper golems, loaded or not (where they were last seen).

From the console the command lists every golem on the server, so each scenario names its
golems ("itest-<scenario>-...") and looks for its own lines.
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
    return re.sub(r"§.", "", c.r.cmd("cgc golems"))


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


@scenario("golems-saved-to-file", "finder", 75, "Golem positions are saved to golems.yml (every 30 seconds), so they survive a restart.")
def _(c):
    named(c, 6, 10, "itest-fs-saved", ",NoAI:1b")

    def check():
        text = open(GOLEMS_FILE).read() if os.path.isfile(GOLEMS_FILE) else ""
        return [("itest-fs-saved" in text, "golems.yml has the golem")]
    return check
