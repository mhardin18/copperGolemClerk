"""world: the world changes while a golem is mid-task.

Each scenario waits until the golem is actually carrying (or fetching) its item,
then breaks something, and checks that nothing crashed, no items were lost or
duplicated, and the golem ends up somewhere sensible.
"""
from arena import Trigger, conserved, expect, scenario


@scenario("target-destroyed", "world", 150, "The log chest a golem is carrying birch logs to is destroyed; the logs go to [Overflow].")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 18, 10, [("oak_log", 1)])
    c.chest("overflow", 8, 4)
    c.sign(8, 4, "[Overflow]")
    c.golem(4, 10)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.holding("birch_log") > 0, lambda: c.remove(18, 10))
        return [(t.at is not None, "log chest destroyed while the golem carried the logs"),
                expect(c, "overflow", "birch_log", 16), conserved(c, "birch_log", 16)]
    return check


@scenario("sign-removed", "world", 180, "The [Rail] sign is removed mid-trip; nothing breaks, and the rails end up in a chest (overflow, or the now-unlabeled one via the vanilla fallback).")
def _(c):
    c.chest("copper", 2, 10, [("powered_rail", 8)], block="waxed_copper_chest")
    c.chest("rails", 18, 10)
    c.sign(18, 10, "[Rail]")
    c.chest("overflow", 8, 4)
    c.sign(8, 4, "[Overflow]")
    c.golem(4, 10)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.holding("powered_rail") > 0, lambda: c.remove_sign(18, 10))
        in_chests = c.count("overflow", "powered_rail") + c.count("rails", "powered_rail")
        return [(t.at is not None, "sign removed while the golem carried the rails"),
                (in_chests == 8, f"{in_chests} of 8 rails are in the overflow or former rails chest"),
                conserved(c, "powered_rail", 8)]
    return check


@scenario("overflow-gone-during-fetch", "world", 120, "The overflow chest is destroyed while a golem walks to fetch from it; the golem gives up cleanly.")
def _(c):
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    c.chest("overflow", 18, 4, [("granite", 16)])
    c.sign(18, 4, "[Overflow]")
    c.chest("stone", 10, 16, [("andesite", 1)])
    c.golem(4, 10)
    t = Trigger()

    def fetching():
        return any("fetching GRANITE" in l and c.in_cell(l) for l in c.log_lines())

    def check():
        t.fire_when(fetching, lambda: c.remove(18, 4))
        waited = t.since() >= 30
        return [(t.at is not None, "overflow chest destroyed after the fetch started"),
                (waited, f"waited {t.since():.0f}s of 30s after removal"),
                (c.holding() == 0, f"golem holding {c.holding()} items (want 0)"),
                expect(c, "stone", "granite", 0)]
    return check


@scenario("return-target-missing", "world", 420, "The copper chest an item came from is destroyed; the item is returned to another copper chest.")
def _(c):
    c.chest("copper", 2, 10, [("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("spare", 2, 18, block="waxed_copper_chest")
    c.chest("busy", 18, 10, [("dirt", 64)] * 27)
    c.golem(4, 10)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.holding("sculk_shrieker") > 0, lambda: c.remove(2, 10))
        return [(t.at is not None, "source copper chest destroyed while the golem carried the item"),
                expect(c, "spare", "sculk_shrieker", 1), (c.holding() == 0, f"golem holding {c.holding()} (want 0)")]
    return check


@scenario("golem-killed-carrying", "world", 120, "A golem is killed while carrying 16 birch logs; exactly 16 drop on the ground.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 18, 10, [("oak_log", 1)])
    c.golem(4, 10)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.holding("birch_log") > 0, lambda: c.r.cmd(f"kill {c.selector('copper_golem')}"))
        return [(t.at is not None, "golem killed while carrying"),
                (c.dropped("birch_log") == 16, f"{c.dropped('birch_log')} birch logs on the ground (want 16)"),
                conserved(c, "birch_log", 16)]
    return check


@scenario("riding-golem", "world", 60, "A golem sitting in a boat (vanilla search range 1 block) causes no errors and loses nothing.")
def _(c):
    c.chest("copper", 3, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 14, 10, [("oak_log", 1)])
    x, y, z = c.abs(5, 10)
    c.run(f'summon minecraft:oak_boat {x + 0.5} {y} {z + 0.5} {{Passengers:[{{id:"minecraft:copper_golem",active_effects:[{{id:"minecraft:luck",duration:-1,show_particles:0b}}]}}]}}')

    def check():
        return [(c.elapsed() >= 45, f"ran {c.elapsed():.0f}s of 45s"), conserved(c, "birch_log", 16),
                (c.golems() == 1, f"{c.golems()} golem(s) present (want 1)")]
    return check


@scenario("chunk-unloaded-mid-trip", "world", 180, "The platform's chunks unload while a golem carries logs, then reload; the logs still arrive.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 18, 10, [("oak_log", 1)])
    c.golem(4, 10)
    unload, reload = Trigger(), Trigger()

    def check():
        unload.fire_when(lambda: c.holding("birch_log") > 0, lambda: c.forceload(False))
        reload.fire_when(lambda: unload.since() >= 10, lambda: c.forceload(True))
        if reload.at is None:
            return [(False, "waiting to unload/reload the chunks")]
        return [expect(c, "logs", "birch_log", 16), conserved(c, "birch_log", 16)]
    return check


@scenario("double-chest-split", "world", 150, "The near half of a double [ore] chest is broken mid-trip; the far half keeps its sign and takes the ore.")
def _(c):
    c.chest("copper", 2, 10, [("coal_ore", 8)], block="waxed_copper_chest")
    c.double_chest("ores", 18, 10)  # left half at 18 (sign), right half at 17
    c.sign(18, 10, "[ore]")
    c.golem(4, 10)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.holding("coal_ore") > 0, lambda: c.remove(17, 10))
        return [(t.at is not None, "near half broken while the golem carried the ore"),
                expect(c, "ores", "coal_ore", 8), conserved(c, "coal_ore", 8)]
    return check


@scenario("sign-edited-mid-trip", "world", 180, "A sign is changed from [logs] to [stone] mid-trip; the birch logs go elsewhere and the chest then takes granite.")
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("labelled", 18, 10)
    c.sign(18, 10, "[logs]")
    c.chest("logs", 10, 16, [("oak_log", 1)])
    c.golem(4, 10)
    t, added = Trigger(), Trigger()
    x, y, z = c.chests["copper"][0]

    def check():
        t.fire_when(lambda: c.holding("birch_log") > 0, lambda: c.sign(18, 10, "[stone]"))
        added.fire_when(lambda: c.count("logs", "birch_log") == 16,
                        lambda: c.run(f"item replace block {x} {y} {z} container.1 with minecraft:granite 8"))
        return [(t.at is not None, "sign changed while the golem carried the logs"),
                expect(c, "logs", "birch_log", 16), expect(c, "labelled", "birch_log", 0),
                expect(c, "labelled", "granite", 8)]
    return check


@scenario("frame-removed-mid-trip", "world", 180, "The frame labelling a chest for stone pickaxes is removed mid-trip; nothing breaks and nothing is lost.")
def _(c):
    c.chest("copper", 2, 10, [("stone_pickaxe", 1)], block="waxed_copper_chest")
    c.chest("labelled", 18, 10)
    c.frame(18, 10, "stone_pickaxe")
    c.chest("tools", 10, 16, [("wooden_axe", 1)])
    c.golem(4, 10)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.holding("stone_pickaxe") > 0,
                    lambda: c.r.cmd(f"kill {c.selector('item_frame')}"))
        placed = c.count("labelled", "stone_pickaxe") + c.count("tools", "stone_pickaxe")
        return [(t.at is not None, "frame removed while the golem carried the pickaxe"),
                (placed == 1, f"pickaxe is in a chest ({placed} of 1)"), (c.holding() == 0, f"golem holding {c.holding()}")]
    return check


@scenario("overflow-full-uses-empty", "world", 150, "The only [Overflow] chest is full; an item with no home falls back to the empty chest.")
def _(c):
    c.chest("copper", 2, 10, [("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("overflow", 6, 10, [("dirt", 64)] * 27)
    c.sign(6, 10, "[Overflow]")
    c.chest("empty", 14, 10)
    c.golem(4, 10)
    return lambda: [expect(c, "empty", "sculk_shrieker", 1), expect(c, "overflow", "sculk_shrieker", 0)]


@scenario("two-overflow-chests", "world", 150, "Two [Overflow] chests, the nearer one full: the item goes to the one with room.")
def _(c):
    c.chest("copper", 2, 10, [("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("full", 6, 10, [("dirt", 64)] * 27)
    c.sign(6, 10, "[Overflow]")
    c.chest("roomy", 16, 10)
    c.sign(16, 10, "[Overflow]")
    c.golem(4, 10)
    return lambda: [expect(c, "roomy", "sculk_shrieker", 1), expect(c, "full", "sculk_shrieker", 0)]


@scenario("double-copper-source", "world", 150, "A double copper chest as the source: items from both halves are sorted.")
def _(c):
    x, y, z = c.abs(3, 10)
    c.run(f"setblock {x} {y} {z} minecraft:waxed_copper_chest[facing=south,type=left]")
    c.run(f"setblock {x - 1} {y} {z} minecraft:waxed_copper_chest[facing=south,type=right]")
    c.run(f"item replace block {x} {y} {z} container.0 with minecraft:birch_log 16")
    c.run(f"item replace block {x - 1} {y} {z} container.0 with minecraft:spruce_log 16")
    c.chests["copper"] = [(x, y, z), (x - 1, y, z)]
    c.chest("logs", 16, 10, [("oak_log", 1)])
    c.golem(5, 10)
    return lambda: [expect(c, "logs", "birch_log", 16), expect(c, "logs", "spruce_log", 16)]


@scenario("nowhere-to-return", "world", 300, "No home and the only copper chest destroyed: the golem keeps the item (nothing lost, no errors) and keeps trying.")
def _(c):
    c.chest("copper", 2, 10, [("sculk_shrieker", 1)], block="waxed_copper_chest")
    c.chest("busy", 14, 10, [("dirt", 64)] * 27)
    c.golem(4, 10)
    t = Trigger()

    def gave_up_returning():
        return any("no copper chest to return it to" in l and "SCULK_SHRIEKER" in l and c.in_cell(l)
                   for l in c.log_lines())

    def check():
        t.fire_when(lambda: c.holding("sculk_shrieker") > 0, lambda: c.remove(2, 10))
        waited = t.since() >= 150
        return [(t.at is not None, "copper chest destroyed while the golem carried the item"),
                (waited, f"waited {t.since():.0f}s of 150s (past the 120s return point)"),
                (c.holding("sculk_shrieker") == 1, f"golem holding {c.holding('sculk_shrieker')} shrieker (want 1)"),
                (gave_up_returning(), "log says there was no copper chest to return it to")]
    return check
