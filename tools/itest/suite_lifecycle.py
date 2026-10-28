"""lifecycle: reloads and restarts while golems are mid-task."""
import sys
import time

from arena import Y, Trigger, conserved, expect, scenario


@scenario("reload-mid-trip", "lifecycle", 150, "/coppergolemclerk reload while a golem carries logs; the logs still arrive.", alone=True)
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 18, 10, [("oak_log", 1)])
    c.golem(4, 10)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.holding("birch_log") > 0, lambda: c.r.cmd("coppergolemclerk reload"))
        return [(t.at is not None, "reloaded while the golem carried the logs"),
                expect(c, "logs", "birch_log", 16), conserved(c, "birch_log", 16)]
    return check


@scenario("reload-during-return", "lifecycle", 420, "/coppergolemclerk reload 40s into a no-home item's wait; it is still returned on time.", alone=True)
def _(c):
    c.chest("copper", 2, 10, [("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("busy", 18, 10, [("dirt", 64)] * 27)
    c.golem(4, 10)
    picked, reload = Trigger(), Trigger()

    def check():
        picked.fire_when(lambda: c.holding("sculk_shrieker") > 0, lambda: None)
        reload.fire_when(lambda: picked.since() >= 40, lambda: c.r.cmd("coppergolemclerk reload"))
        return [(reload.at is not None, "reloaded while the item had no home"),
                expect(c, "copper", "sculk_shrieker", 1), (c.holding() == 0, f"golem holding {c.holding()} (want 0)")]
    return check


@scenario("server-restart-carrying", "lifecycle", 600, "The server restarts while a golem carries logs; afterwards the logs still arrive. Needs you to restart the server.",
          alone=True, manual=True)
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 18, 10, [("oak_log", 1)])
    c.golem(4, 10)
    state = {"restarted": False}

    def check():
        if not state["restarted"]:
            if c.holding("birch_log") == 0:
                return [(False, "waiting for the golem to pick up the logs")]
            print("\n  >>> The golem is carrying the logs. Restart the server now:  sudo systemctl restart mcd", flush=True)
            c.r.reconnect_after_restart()
            state["restarted"] = True
            print("  >>> Server is back; checking the delivery.", flush=True)
        return [expect(c, "logs", "birch_log", 16), conserved(c, "birch_log", 16)]
    return check


@scenario("reload-spam", "lifecycle", 150, "Ten reloads in quick succession while golems sort 32 logs: no errors, nothing lost, everything delivered.", alone=True)
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16), ("spruce_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 16, 10, [("oak_log", 1)])
    c.golem(4, 10)
    c.golem(4, 12)
    t = Trigger()

    def spam():
        for _ in range(10):
            c.r.cmd("coppergolemclerk reload")

    def check():
        t.fire_when(lambda: c.holding() > 0, spam)
        return [(t.at is not None, "reloaded 10 times while golems carried logs"),
                expect(c, "logs", "birch_log", 16), expect(c, "logs", "spruce_log", 16), conserved(c, "birch_log", 16)]
    return check


@scenario("trapdoor-after-restart", "lifecycle", 600, "Trapdoor avoidance survives a restart: one golem saved in an unloaded area and loaded again afterwards, one kept loaded through startup; both are teleported next to trapdoor-covered holes and neither falls in. Restarts pluginDev itself (sudo -n systemctl restart mcd).",
          alone=True, manual=True)
def _(c):
    import subprocess
    from suite_setup import OPEN_TRAPDOOR, golems_in, trapdoor_holes
    lanes = (5, 15)
    for dz in lanes:
        trapdoor_holes(c, OPEN_TRAPDOOR, (dz - 1, dz, dz + 1))
        c.chest(f"copper{dz}", 2, dz, [("birch_log", 8)], block="waxed_copper_chest")
        c.chest(f"logs{dz}", 14, dz, [("oak_log", 1)])

    def park(name, dx, dz):
        """A glass box holding one golem, away from the platform. Chunks within two chunks of a
        force-loaded one stay loaded, so the one that must unload is 200 blocks west."""
        x, y, z = c.abs(dx, dz)
        c.run(f"forceload add {x} {z}")
        c.run(f"fill {x - 1} {y - 1} {z - 1} {x + 1} {y + 2} {z + 1} minecraft:glass")
        c.run(f"fill {x} {y} {z} {x} {y + 1} {z} minecraft:air")
        c.run(f'summon minecraft:copper_golem {x + 0.5} {y} {z + 0.5} {{Tags:["itest_{name}"],'
              'active_effects:[{id:"minecraft:luck",duration:-1,show_particles:0b,show_icon:0b}]}')
        return x, y, z

    spots = {"unloaded": park("unloaded", -200, 10), "loaded": park("loaded", 4, 45)}
    state = {"step": 0}

    def present(name):
        return "passed" in c.r.cmd(f"execute if entity @e[tag=itest_{name}]")

    def fell():
        return any(golems_in(c, 8, Y - 2, 0, dz - 1, dz + 1) for dz in lanes)

    def check():
        if state["step"] == 0:
            x, _, z = spots["unloaded"]
            c.r.cmd(f"forceload remove {x} {z}")
            state["step"], state["t"] = 1, c.elapsed()
        if state["step"] == 1:
            if present("unloaded"):
                return [(False, "waiting for the unloaded golem's area to unload")]
            print("\n  >>> Golem saved in an unloaded area. Restarting pluginDev...", flush=True)
            subprocess.run(["sudo", "-n", "systemctl", "restart", "mcd"], check=True)
            c.r.reconnect_after_restart()
            print("  >>> Server is back.", flush=True)
            x, _, z = spots["unloaded"]
            c.r.cmd(f"forceload add {x} {z}")  # loads the golem, like a player walking up
            state["step"] = 2
        if state["step"] == 2:
            if not (present("unloaded") and present("loaded")):
                return [(False, "waiting for both golems to load")]
            for name, dz in (("unloaded", 5), ("loaded", 15)):
                x, y, z = c.abs(4, dz)
                c.r.cmd(f"tp @e[tag=itest_{name},limit=1] {x + 0.5} {y} {z + 0.5}")
            for name in spots:
                x, y, z = spots[name]
                c.r.cmd(f"fill {x - 1} {y - 1} {z - 1} {x + 1} {y + 2} {z + 1} minecraft:air")
                c.r.cmd(f"forceload remove {x} {z}")
            state["step"], state["fell"] = 3, False
        state["fell"] = state["fell"] or fell()
        return [expect(c, "logs5", "birch_log", 8), expect(c, "logs15", "birch_log", 8),
                (not state["fell"], "no golem fell through a trapdoor")]
    return check
