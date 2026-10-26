"""lifecycle: reloads and restarts while golems are mid-task."""
import sys
import time

from arena import Trigger, conserved, expect, scenario


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
