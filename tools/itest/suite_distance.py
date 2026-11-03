"""distance: search-distance, golems looking past vanilla's 32 blocks.

The platform is 21 blocks wide, so chests further out sit on a walled walkway east of it
(a 3-wide glowstone floor with glass walls), about 50 blocks from the golem: past vanilla's
32, within a search-distance of 64. The default (32) ignoring such a chest is checked by
edge/home-out-of-range-side.
"""
from arena import SIZE, expect, scenario
from suite_options import set_options


def walkway(c, to_dx=56, dz=10):
    """Open the platform's east wall at dz and lay a walled 3-wide walkway out to to_dx."""
    x, y, z = c.abs(0, dz)
    s = SIZE
    c.run(f"fill {x + s} {y} {z - 1} {x + s} {y + 2} {z + 1} minecraft:air")
    c.run(f"fill {x + s} {y - 1} {z - 2} {x + to_dx} {y - 1} {z + 2} minecraft:glowstone")
    c.run(f"fill {x + s} {y} {z - 2} {x + to_dx} {y + 2} {z - 2} minecraft:glass")
    c.run(f"fill {x + s} {y} {z + 2} {x + to_dx} {y + 2} {z + 2} minecraft:glass")
    c.run(f"fill {x + to_dx} {y} {z - 2} {x + to_dx} {y + 2} {z + 2} minecraft:glass")
    c.cleanup += [f"kill @e[type=copper_golem,x={x + s},y={y - 2},z={z - 3},dx={to_dx - s + 1},dy=6,dz=6]",
                  f"kill @e[type=item,x={x + s},y={y - 2},z={z - 3},dx={to_dx - s + 1},dy=6,dz=6]",
                  f"fill {x + s} {y - 1} {z - 2} {x + to_dx} {y + 2} {z + 2} minecraft:air"]


@scenario("far-deliver", "distance", 240, "search-distance: 64: the only [logs] chest is 50 blocks away (past vanilla's 32): the golem walks there and the logs go in, not to [Overflow].", alone=True)
def _(c):
    set_options(c, search_distance="64")
    walkway(c)
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("far", 54, 10, [("oak_log", 1)])
    c.sign(54, 10, "[logs]")
    c.chest("overflow", 14, 16)
    c.sign(14, 16, "[Overflow]")
    c.golem(4, 10)

    fx, fy, fz = c.chests["far"][0]

    def check():
        # The line names the far chest, which is off the platform, so match its position (not in_cell).
        heading = any(f"at {fx},{fy},{fz}" in l and "past vanilla's range" in l for l in c.log_lines())
        return [(heading, "the golem headed for the far chest" if heading else "no 'past vanilla's range' line yet"),
                expect(c, "far", "birch_log", 16), expect(c, "overflow", "birch_log", 0)]
    return check


@scenario("far-pickup", "distance", 240, "search-distance: 64: the only copper chest with anything in it is 50 blocks away: an idle golem walks there, takes the birch logs and sorts them into the log chest.", alone=True)
def _(c):
    set_options(c, search_distance="64")
    walkway(c)
    c.chest("copper", 54, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 14, 16, [("oak_log", 1)])
    c.golem(4, 10)
    return lambda: [expect(c, "logs", "birch_log", 16), expect(c, "copper", "birch_log", 0)]


@scenario("search-distance-below-vanilla", "distance", 30, "search-distance: 8 is warned about and raised to 32 (it can't go below vanilla).", alone=True)
def _(c):
    set_options(c, search_distance="8")
    return lambda: [("search-distance must be 32 to 128 (it's 8); using 32." in "\n".join(c.log_lines()),
                     "warned and used 32")]


@scenario("search-distance-above-max", "distance", 30, "search-distance: 500 is warned about and lowered to 128.", alone=True)
def _(c):
    set_options(c, search_distance="500")
    return lambda: [("search-distance must be 32 to 128 (it's 500); using 128." in "\n".join(c.log_lines()),
                     "warned and used 128")]
