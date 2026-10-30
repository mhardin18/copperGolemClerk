"""portals: copper golems don't go through Nether portals (avoid-portals).

A real portal (obsidian frame, portal blocks inside) is built on the platform and a golem
(no AI, so it stays put) is put inside it. Mobs go through a portal as soon as they're in
it, so a golem still on the platform after a while was kept from going. With the setting
off, the golem goes to the Nether; it's killed there afterwards.
"""
from arena import Trigger, _count, scenario
from suite_modes import LUCK, VANILLA
from suite_options import set_options


def portal(c, dx=8, dz=15):
    x, y, z = c.abs(dx, dz)
    c.run(f"fill {x} {y} {z} {x + 3} {y + 4} {z} minecraft:obsidian")
    c.run(f"fill {x + 1} {y + 1} {z} {x + 2} {y + 3} {z} minecraft:nether_portal[axis=x]")
    return x + 1.5, y + 1, z + 0.5


def golem_in_portal(c, extra=""):
    px, py, pz = portal(c)
    c.run(f"summon minecraft:copper_golem {px} {py} {pz} {{NoAI:1b,active_effects:[{LUCK}]{extra}}}")
    # Wherever the golem lands in the Nether (about 1/8 of the coordinates), kill it afterwards.
    nx, nz = int(px) // 8, int(pz) // 8
    box = f"x={nx - 64},y=0,z={nz - 64},dx=128,dy=128,dz=128"
    c.cleanup.append(f"execute in minecraft:the_nether run kill @e[type=minecraft:copper_golem,{box}]")
    return box


def in_nether(box):
    return f"execute in minecraft:the_nether if entity @e[type=minecraft:copper_golem,{box}]"


@scenario("portal-golem-stays", "portals", 45, "A copper golem standing in a Nether portal doesn't go through: it's still on the platform 20 seconds later.")
def _(c):
    golem_in_portal(c)
    t = Trigger()

    def check():
        t.fire_when(lambda: True, lambda: None)
        kept = [l for l in c.log_lines() if "Kept a copper golem" in l and c.in_cell(l)]
        return [(t.since() > 20, "waited 20s"), (c.golems() == 1, f"{c.golems()} golem on the platform (want 1)"),
                (bool(kept), "logged that it was kept from going through")]
    return check


@scenario("option-avoid-portals-off", "portals", 60, "avoid-portals: false: the copper golem in the portal goes to the Nether.", alone=True)
def _(c):
    set_options(c, avoid_portals="false")
    box = golem_in_portal(c)
    return lambda: [(c.golems() == 0, f"{c.golems()} golem left on the platform (want 0)"),
                    (_count(c.r.cmd(in_nether(box))) >= 1, "it's in the Nether")]


@scenario("portal-vanilla-mode-goes", "portals", 60, "A golem switched to vanilla isn't held back: it goes through the portal as in vanilla.", alone=True)
def _(c):
    golem_in_portal(c, "," + VANILLA)
    return lambda: [(c.golems() == 0, f"{c.golems()} golem left on the platform (want 0: it went through)")]
