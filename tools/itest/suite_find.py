"""find: /cgc find <item> <x> <y> <z> [world] from the console.

The console sees every container; what players may see (claims, locks) needs a player
and is checked by hand. Searches are centred on the platform's middle (10, 10).
"""
import re

from arena import scenario
from suite_options import set_options


def find(c, what, dx=10, dz=10):
    x, y, z = c.abs(dx, dz)
    return re.sub(r"§.", "", c.r.cmd(f"cgc find {what} {x} {y} {z}"))


def has(reply, text):
    return text in reply, f"says '{text}'" + ("" if text in reply else f"; got: {reply.strip()[:300]}")


SHULKER = 'shulker_box[minecraft:container=[{slot:0,item:{id:"minecraft:diamond",count:5}}]]'


@scenario("find-item", "find", 30, "/cgc find birch_log: the total, each chest nearest first with its label, and a double chest counted once.")
def _(c):
    c.chest("near", 12, 10, [("birch_log", 10)])
    c.chest("far", 20, 18, [("birch_log", 3), ("oak_log", 7)])
    c.sign(20, 18, "[Logs]")
    c.double_chest("double", 4, 14, [("birch_log", 20)])
    c.chest("other", 16, 4, [("dirt", 5)])

    def check():
        reply = find(c, "birch_log")
        lines = [l for l in reply.splitlines() if " in chest" in l]
        order = [int(l.split()[0]) for l in lines]
        return [has(reply, "33 birch log in 3 containers"),
                (order == [10, 20, 3], f"nearest first: counts {order} (want [10, 20, 3])"),
                has(reply, "in chest [Logs] at"),
                ("dirt" not in reply, "dirt chest not listed")]
    return check


@scenario("find-group", "find", 30, "/cgc find logs (a group) counts every kind of log; several words work: /cgc find birch log.")
def _(c):
    c.chest("a", 12, 10, [("birch_log", 4)])
    c.chest("b", 14, 10, [("oak_log", 6)])

    def check():
        group = find(c, "logs")
        words = find(c, "birch log")
        return [has(group, "10 logs (group logs) in 2 containers"), has(words, "4 birch log in 1 container")]
    return check


@scenario("find-in-shulker", "find", 30, "/cgc find diamond counts diamonds inside a shulker box in a chest, and says so.")
def _(c):
    c.chest("box", 12, 10, [(SHULKER, 1), ("diamond", 2)])

    def check():
        reply = find(c, "diamond")
        return [has(reply, "7 diamond in 1 container"), has(reply, "(5 in shulker boxes)")]
    return check


@scenario("find-other-containers", "find", 30, "/cgc find looks in barrels and copper chests too.")
def _(c):
    c.chest("barrel", 12, 10, [("granite", 3)], block="barrel")
    c.chest("copper", 14, 10, [("granite", 4)], block="waxed_copper_chest")

    def check():
        reply = find(c, "granite")
        return [has(reply, "7 granite in 2 containers"), has(reply, "in barrel at"), has(reply, "in waxed copper chest at")]
    return check


@scenario("find-nothing", "find", 30, "/cgc find for an item nobody has says none; a word that isn't an item says so; no item gives the usage.")
def _(c):
    c.chest("a", 12, 10, [("dirt", 1)])
    x, y, z = c.abs(10, 10)

    def check():
        return [has(find(c, "emerald"), "No emerald in containers within"),
                has(find(c, "frobnitz"), "'frobnitz' isn't an item, group, pattern or tag"),
                has(re.sub(r"§.", "", c.r.cmd("cgc find")), "Usage: /cgc find <item> <x> <y> <z> [world]")]
    return check


@scenario("find-radius", "find", 40, "find-radius: 5 leaves out a chest 8 blocks away.", alone=True)
def _(c):
    set_options(c, find_radius="5")
    c.chest("near", 13, 10, [("birch_log", 2)])
    c.chest("far", 18, 10, [("birch_log", 9)])
    return lambda: [has(find(c, "birch_log"), "2 birch log in 1 container within 5 blocks")]


@scenario("find-disabled", "find", 30, "find-enabled: false turns /cgc find off.", alone=True)
def _(c):
    set_options(c, find_enabled="false")
    c.chest("a", 12, 10, [("birch_log", 2)])
    return lambda: [has(find(c, "birch_log"), "/cgc find is turned off on this server")]
