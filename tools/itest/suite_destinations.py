"""destinations: barrels and shulker boxes as destinations (destination-containers).

Each scenario changes destination-containers (so runs alone); the runner puts the
config back afterwards.
"""
from arena import Trigger, expect, scenario
from suite_options import set_options

ALL = "[chest, trapped_chest, barrel, shulker_box]"


def logs_copper(c, n=16):
    c.chest("copper", 2, 10, [("birch_log", n)], block="waxed_copper_chest")
    c.golem(4, 10)


@scenario("dest-barrel-labelled", "destinations", 150, "Barrels allowed, the only [logs] container is a barrel (no chests in range): the golem takes the logs there.", alone=True)
def _(c):
    set_options(c, destination_containers=ALL)
    logs_copper(c)
    c.chest("barrel", 12, 10, block="barrel")
    c.sign(12, 10, "[logs]")
    return lambda: [expect(c, "barrel", "birch_log", 16)]


@scenario("dest-barrel-by-contents", "destinations", 150, "Barrels allowed: a barrel holding an oak log gets the birch logs, not the nearer empty chest.", alone=True)
def _(c):
    set_options(c, destination_containers=ALL)
    logs_copper(c)
    c.chest("empty", 7, 10)
    c.chest("barrel", 14, 10, [("oak_log", 1)], block="barrel")
    return lambda: [expect(c, "barrel", "birch_log", 16), expect(c, "empty", "*", 0)]


@scenario("dest-barrel-off", "destinations", 120, "Default settings: a [logs] barrel is ignored; the logs go to [Overflow].", alone=True)
def _(c):
    logs_copper(c)
    c.chest("barrel", 12, 10, block="barrel")
    c.sign(12, 10, "[logs]")
    c.chest("overflow", 16, 10)
    c.sign(16, 10, "[Overflow]")
    return lambda: [expect(c, "overflow", "birch_log", 16), expect(c, "barrel", "*", 0)]


@scenario("dest-shulker-labelled", "destinations", 150, "Shulker boxes allowed: a [logs] shulker box gets the logs.", alone=True)
def _(c):
    set_options(c, destination_containers=ALL)
    logs_copper(c)
    c.chest("shulker", 12, 10, block="lime_shulker_box")
    c.sign(12, 10, "[logs]")
    return lambda: [expect(c, "shulker", "birch_log", 16)]


@scenario("dest-shulker-refuses-shulker", "destinations", 150, "A shulker box item with no home: it goes to the [Overflow] chest, never into the [Overflow] shulker box.", alone=True)
def _(c):
    set_options(c, destination_containers=ALL)
    c.chest("copper", 2, 10, [("red_shulker_box", 1)], block="waxed_copper_chest")
    c.golem(4, 10)
    c.chest("oshulker", 8, 10, block="lime_shulker_box")
    c.sign(8, 10, "[Overflow]")
    c.chest("ochest", 16, 10)
    c.sign(16, 10, "[Overflow]")
    return lambda: [expect(c, "ochest", "red_shulker_box", 1), expect(c, "oshulker", "*", 0)]


@scenario("dest-shulker-blocked", "destinations", 150, "A [logs] shulker box with a block in front of its lid can't open: the logs go to [Overflow].", alone=True)
def _(c):
    set_options(c, destination_containers=ALL)
    logs_copper(c)
    c.chest("shulker", 12, 10, block="lime_shulker_box")  # facing south: the lid opens towards +z
    c.sign(12, 10, "[logs]")
    c.block(12, 11, "stone")
    c.chest("overflow", 16, 10)
    c.sign(16, 10, "[Overflow]")
    return lambda: [expect(c, "overflow", "birch_log", 16), expect(c, "shulker", "*", 0)]


@scenario("dest-barrels-only", "destinations", 150, "destination-containers: [barrel]: a [logs] chest and a [logs] barrel; the logs go to the barrel, never the chest.", alone=True)
def _(c):
    set_options(c, destination_containers="[barrel]")
    logs_copper(c)
    c.chest("chest", 7, 10)
    c.sign(7, 10, "[logs]")
    c.chest("barrel", 14, 10, block="barrel")
    c.sign(14, 10, "[logs]")
    return lambda: [expect(c, "barrel", "birch_log", 16), expect(c, "chest", "*", 0)]


@scenario("dest-bad-name", "destinations", 120, "destination-containers with an unknown name: a warning is logged and chests still work.", alone=True)
def _(c):
    set_options(c, destination_containers="[chest, frobnitz]")
    logs_copper(c)
    c.chest("logs", 10, 10)
    c.sign(10, 10, "[logs]")
    return lambda: [expect(c, "logs", "birch_log", 16),
                    (any("frobnitz" in l and "destination-containers" in l for l in c.log_lines()), "warning logged")]


@scenario("dest-chest-dropped-mid-walk", "destinations", 150, "destination-containers loses chest while a golem walks to the [logs] chest: it doesn't put the logs there.", alone=True)
def _(c):
    c.chest("copper", 2, 10, [("birch_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 18, 10)
    c.sign(18, 10, "[logs]")
    c.golem(4, 10)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.holding("birch_log") > 0, lambda: set_options(c, destination_containers="[barrel]"))
        if t.at is None:
            return [(False, "waiting for the golem to pick up the logs")]
        return [(t.since() > 30, "waited 30s after taking chests out"), expect(c, "logs", "*", 0)]
    return check


@scenario("dest-fetch-from-overflow-barrel", "destinations", 150, "Barrels allowed: an idle golem fetches granite out of an [Overflow] barrel into the [stone] chest.", alone=True)
def _(c):
    set_options(c, destination_containers=ALL)
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    c.chest("overflow", 8, 10, [("granite", 8)], block="barrel")
    c.sign(8, 10, "[Overflow]")
    c.chest("stone", 14, 10)
    c.sign(14, 10, "[stone]")
    c.golem(4, 10)
    return lambda: [expect(c, "stone", "granite", 8), expect(c, "overflow", "*", 0)]


@scenario("dest-barrel-out-of-reach", "destinations", 120, "Barrels allowed, deliver-reach 2: a [logs] barrel 3 rows up (on two blocks) is out of reach; the logs go to [Overflow].", alone=True)
def _(c):
    set_options(c, destination_containers=ALL)
    logs_copper(c)
    c.block(12, 10, "stone")
    c.block(12, 10, "stone", dy=1)
    c.block(12, 10, "stone", dy=2)
    c.chest("barrel", 12, 10, block="barrel", dy=3)
    c.sign(12, 10, "[logs]", dy=3)
    c.chest("overflow", 16, 10)
    c.sign(16, 10, "[Overflow]")
    return lambda: [expect(c, "overflow", "birch_log", 16), expect(c, "barrel", "*", 0)]


@scenario("dest-full-barrel-alert", "destinations", 150, "Barrels allowed: a full [logs] barrel gets a full-chest alert naming it, and the logs go to [Overflow].", alone=True)
def _(c):
    set_options(c, destination_containers=ALL)
    logs_copper(c)
    c.chest("barrel", 10, 10, [("oak_log", 64)] * 27, block="barrel")
    c.sign(10, 10, "[logs]")
    c.chest("overflow", 16, 10)
    c.sign(16, 10, "[Overflow]")
    x, y, z = c.abs(10, 10)
    return lambda: [expect(c, "overflow", "birch_log", 16),
                    (any("Alert" in l and f"[logs] chest at {x}, {y}, {z} is full" in l for l in c.log_lines()),
                     "alert about the full barrel")]


@scenario("dest-many-barrels-tick-time", "destinations", 240, "Barrels allowed, 363 barrels (90 labelled) in range, 20 golems each carrying an item with no home: the 2-second delivery checks keep ticks under 40 ms and within 20 ms of the baseline.", alone=True)
def _(c):
    from suite_perf import LABELS, mspt
    baseline = mspt(c)
    set_options(c, destination_containers=ALL)
    shelf = 0
    for dy in (3, 5, 7):
        for dx in range(0, 21, 2):
            for dz in range(0, 21, 2):
                c.chest("shelf", dx, dz, [("stone", 1)], block="barrel", dy=dy)
                if shelf % 4 == 0:
                    c.sign(dx, dz, LABELS[(shelf // 4) % len(LABELS)], dy=dy)
                shelf += 1
    for n in range(20):
        x, y, z = c.abs(3 + (n % 10), 8 + (n // 10) * 4)
        c.run(f"summon minecraft:copper_golem {x + 0.5} {y} {z + 0.5} "
              f'{{active_effects:[{{id:"minecraft:luck",duration:-1,show_particles:0b}}],'
              f'equipment:{{mainhand:{{id:"minecraft:sculk_shrieker",count:1}}}}}}')
    samples = []

    def check():
        if c.elapsed() >= 15 and len(samples) < 8 and int(c.elapsed()) % 6 < 3:
            value = mspt(c)
            if value is not None:
                samples.append(value)
        worst = max(samples) if samples else None
        return [(len(samples) >= 5, f"tick time samples: {samples} ms (baseline {baseline} ms)"),
                (worst is not None and worst < 40, f"worst 5-second average {worst} ms (want under 40)"),
                (worst is not None and baseline is not None and worst - baseline < 20,
                 f"slowdown {None if worst is None or baseline is None else round(worst - baseline, 1)} ms (want under 20)")]
    return check
