"""conflicts: other plugins that also decide where golems put things.

Uses the test-only GolemTestHooks plugin (tools/itest/hooks), which acts only in the
test arena, keyed on the block under a chest:
  gold block    - another plugin forbids the chest, before CopperGolemClerk (LOWEST)
  diamond block - another plugin forces the chest to accept, before CopperGolemClerk
  emerald block - another plugin forbids the chest after CopperGolemClerk said yes (HIGHEST)
Scenarios are skipped when GolemTestHooks isn't loaded.
"""
import re

from arena import Skip, conserved, expect, scenario


def require_hooks(c):
    plugins = re.sub(r"§.", "", c.r.cmd("plugins"))
    if "GolemTestHooks" not in plugins:
        raise Skip("GolemTestHooks isn't loaded (copy tools/itest/hooks' jar to pluginDev and restart)")


@scenario("other-plugin-forbids-first", "conflicts", 150, "Another plugin forbids the perfect log chest before us: we never override it; the logs go to [Overflow].", alone=True)
def _(c):
    require_hooks(c)
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 14, 10, [("oak_log", 1)])
    c.block(14, 10, "gold_block", dy=-1)
    c.chest("overflow", 8, 4)
    c.sign(8, 4, "[Overflow]")
    c.golem(4, 10)
    return lambda: [expect(c, "overflow", "birch_log", 16), expect(c, "logs", "birch_log", 0), conserved(c, "birch_log", 16)]


@scenario("other-plugin-forces-accept", "conflicts", 150, "Another plugin forces a [stone]-labelled chest to accept: we don't overturn its yes, so the birch logs can go there.")
def _(c):
    require_hooks(c)
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("forced", 7, 10)
    c.sign(7, 10, "[stone]")
    c.block(7, 10, "diamond_block", dy=-1)
    c.golem(4, 10)
    return lambda: [expect(c, "forced", "birch_log", 16), conserved(c, "birch_log", 16)]


@scenario("other-plugin-forbids-last", "conflicts", 150, "Another plugin forbids the log chest after we approved it: the logs still end up in [Overflow], none lost.", alone=True)
def _(c):
    require_hooks(c)
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 14, 10, [("oak_log", 1)])
    c.block(14, 10, "emerald_block", dy=-1)
    c.chest("overflow", 8, 4)
    c.sign(8, 4, "[Overflow]")
    c.golem(4, 10)
    return lambda: [expect(c, "overflow", "birch_log", 16), expect(c, "logs", "birch_log", 0), conserved(c, "birch_log", 16)]
