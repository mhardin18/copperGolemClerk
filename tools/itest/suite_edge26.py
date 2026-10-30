"""edge26: edge and exception cases for the 2.6.0 features (carry-amount, /cgc find, old configs).

Most scenarios here change settings, so they run on their own.
"""
import re

from arena import CONFIG, Trigger, conserved, expect, scenario
from suite_options import set_options


def say(c, command):
    return re.sub(r"§.", "", c.r.cmd(command))


def has(reply, text):
    return text in reply, f"says '{text}'" + ("" if text in reply else f"; got: {reply.strip()[:300]}")


# ---- carry-amount with the things that can happen to a golem's load

@scenario("carry64-killed", "edge26", 150, "carry-amount 64: a golem killed while carrying 64 birch logs drops all 64.", alone=True)
def _(c):
    set_options(c, carry_amount="64")
    c.chest("copper", 2, 10, [("birch_log", 64)], block="waxed_copper_chest")
    c.chest("logs", 18, 10, [("oak_log", 1)])
    c.golem(4, 10)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.holding("birch_log") == 64, lambda: c.r.cmd(f"kill {c.selector('copper_golem')}"))
        return [(t.at is not None, "golem killed while carrying 64"),
                (c.dropped("birch_log") == 64, f"{c.dropped('birch_log')} birch logs on the ground (want 64)"),
                conserved(c, "birch_log", 64)]
    return check


@scenario("carry64-statue", "edge26", 150, "carry-amount 64: a golem carrying 64 granite turns into a statue: all 64 drop, none lost.", alone=True)
def _(c):
    set_options(c, carry_amount="64")
    c.chest("copper", 2, 10, [("granite", 64)], block="waxed_copper_chest")
    c.chest("stone", 18, 10, [("andesite", 1)])
    c.block(16, 10, "glass")
    c.golem(4, 10)
    t = Trigger()
    sel = c.selector("copper_golem")

    def check():
        t.fire_when(lambda: c.holding("granite") == 64,
                    lambda: c.r.cmd(f'data merge entity {sel[:-1]},limit=1] {{weather_state:"oxidized",next_weather_age:1L}}'))
        if t.at is None:
            return [(False, "waiting for the golem to pick up 64 granite")]
        return [(c.golems() == 0, "the golem turned into a statue"), conserved(c, "granite", 64)]
    return check


@scenario("carry64-only-filter", "edge26", 180, "carry-amount 64 with an [Only Logs] copper chest: the golem never tops up with dirt; the logs are sorted and the dirt stays.", alone=True)
def _(c):
    set_options(c, carry_amount="64")
    c.chest("copper", 2, 10, [("dirt", 32), ("birch_log", 20), ("dirt", 32)], block="waxed_copper_chest")
    c.sign(2, 10, "[Only Logs]")
    c.chest("logs", 14, 10, [("oak_log", 1)])
    c.chest("dirt", 10, 14, [("dirt", 1)])
    c.golem(4, 10)
    dirt_seen = [0]

    def check():
        dirt_seen[0] = max(dirt_seen[0], c.holding("dirt"))
        return [expect(c, "logs", "birch_log", 20), expect(c, "copper", "dirt", 64),
                (dirt_seen[0] == 0, f"golem held {dirt_seen[0]} dirt at some point (want never)")]
    return check


@scenario("carry64-not-similar", "edge26", 180, "carry-amount 64: renamed birch logs aren't added to a load of plain ones (or the other way round); all 40 arrive, both kinds intact.", alone=True)
def _(c):
    set_options(c, carry_amount="64")
    c.chest("copper", 2, 10, [("birch_log", 20), ('birch_log[custom_name="Special"]', 20)], block="waxed_copper_chest")
    c.chest("logs", 14, 10, [("oak_log", 1)])
    c.golem(4, 10)
    named = 'birch_log[custom_name="Special"]'
    return lambda: [expect(c, "logs", "birch_log", 40), expect(c, "logs", named, 20),
                    (c.holding("birch_log") == 0, "golem's hands empty")]


@scenario("carry64-little-room", "edge26", 240, "carry-amount 64 but the log chest has room for only 10 more birch logs: 10 go in, the other 54 go to the overflow chest; none lost.", alone=True)
def _(c):
    set_options(c, carry_amount="64")
    c.chest("copper", 2, 10, [("birch_log", 64)], block="waxed_copper_chest")
    c.chest("logs", 10, 10, [("oak_log", 64)] * 26 + [("birch_log", 54)])
    c.chest("overflow", 14, 10)
    c.sign(14, 10, "[Overflow]")
    c.golem(4, 10)
    return lambda: [expect(c, "logs", "birch_log", 64), expect(c, "overflow", "birch_log", 54),
                    conserved(c, "birch_log", 118)]


@scenario("carry64-into-barrel", "edge26", 180, "carry-amount 64 with barrels as destinations: the plugin puts all 64 granite into the labelled barrel.", alone=True)
def _(c):
    set_options(c, carry_amount="64", destination_containers="[chest, trapped_chest, barrel]")
    c.chest("copper", 2, 10, [("granite", 64)], block="waxed_copper_chest")
    c.chest("barrel", 10, 10, block="barrel")
    c.sign(10, 10, "[granite]")
    c.golem(4, 10)
    return lambda: [expect(c, "barrel", "granite", 64), conserved(c, "granite", 64)]


@scenario("carry-changed-mid-carry", "edge26", 180, "carry-amount drops from 64 to 4 while a golem holds 64: nothing is lost and all 64 arrive.", alone=True)
def _(c):
    set_options(c, carry_amount="64")
    c.chest("copper", 2, 10, [("birch_log", 64)], block="waxed_copper_chest")
    c.chest("logs", 18, 10, [("oak_log", 1)])
    c.block(16, 10, "glass")
    c.golem(4, 10)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.holding("birch_log") == 64, lambda: set_options(c, carry_amount="4"))
        return [(t.at is not None, "changed carry-amount while the golem held 64"),
                expect(c, "logs", "birch_log", 64), conserved(c, "birch_log", 64)]
    return check


@scenario("carry64-homeless-returned", "edge26", 240, "carry-amount 64 with an item that has nowhere to go (empty chests off): all 64 go back into the copper chest, none lost.", alone=True)
def _(c):
    set_options(c, carry_amount="64", allow_empty_chests="false", matching_chest_timeout_seconds="5")
    c.chest("copper", 2, 10, [("sculk_shrieker", 64)], block="waxed_copper_chest")
    c.chest("stone", 14, 10, [("andesite", 1)])
    c.golem(4, 10)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.holding("sculk_shrieker") > 16, lambda: None)
        return [(t.at is not None, "golem carried more than 16 at once"),
                expect(c, "copper", "sculk_shrieker", 64), (c.holding("sculk_shrieker") == 0, "hands empty"),
                conserved(c, "sculk_shrieker", 64)]
    return check


@scenario("carry64-stats-count", "edge26", 150, "carry-amount 64: /cgc stats counts all 64 of a delivery, not 16.", alone=True)
def _(c):
    set_options(c, carry_amount="64")
    def today():
        m = re.search(r"today ([\d,]+)", say(c, "cgc stats"))
        return int(m.group(1).replace(",", "")) if m else -1
    before = today()
    c.chest("copper", 2, 10, [("cherry_log", 64)], block="waxed_copper_chest")
    c.chest("logs", 10, 10, [("oak_log", 1)])
    c.golem(4, 10)
    t = Trigger()

    def check():
        t.fire_when(lambda: c.count("logs", "cherry_log") == 64, lambda: None)
        after = today()
        return [expect(c, "logs", "cherry_log", 64), (t.at is not None and t.since() > 5, "delivered, then waited 5s"),
                (after - before == 64, f"today's total went from {before} to {after} (want +64)")]
    return check


# ---- /cgc find: awkward input

def find_at(c, what, dx=10, dz=10, world=""):
    x, y, z = c.abs(dx, dz)
    return say(c, f"cgc find {what} {x} {y} {z} {world}".strip())


@scenario("find-item-ending-in-number", "edge26", 30, "/cgc find music_disc_13 <x> <y> <z>: an item name ending in a number isn't mistaken for a coordinate.")
def _(c):
    c.chest("discs", 12, 10, [("music_disc_13", 1)])
    return lambda: [has(find_at(c, "music_disc_13"), "1 music disc 13 in 1 container")]


@scenario("find-bad-world", "edge26", 30, "/cgc find with an unknown world name says so instead of failing.")
def _(c):
    return lambda: [has(find_at(c, "dirt", world="no_such_world"), "No world named 'no_such_world'")]


@scenario("find-unloaded-spot", "edge26", 30, "/cgc find around a spot nobody has loaded finds nothing and says so, without loading chunks or erroring.")
def _(c):
    def check():
        reply = say(c, "cgc find dirt 2900000 100 2900000")
        return [has(reply, "No dirt in containers within")]
    return check


@scenario("find-pattern-and-tag", "edge26", 30, "/cgc find *_log and /cgc find #minecraft:logs both find birch and oak logs.")
def _(c):
    c.chest("a", 12, 10, [("birch_log", 3), ("oak_log", 4)])
    return lambda: [has(find_at(c, "*_log"), "7 *_log (pattern *_LOG)"),
                    has(find_at(c, "#minecraft:logs"), "7 #minecraft:logs (item tag")]


@scenario("find-empty-shulker-and-nested", "edge26", 30, "/cgc find counts items in shulker boxes inside a chest, ignores an empty shulker box, and finds shulker boxes themselves.")
def _(c):
    full = 'shulker_box[minecraft:container=[{slot:0,item:{id:"minecraft:emerald",count:9}}]]'
    c.chest("a", 12, 10, [(full, 1), ("shulker_box", 1)])
    return lambda: [has(find_at(c, "emerald"), "9 emerald in 1 container"), has(find_at(c, "shulker_box"), "2 shulker_box (item and its dyed versions) in 1 container")]


# ---- an old config.yml (like survival's) without the 2.6.0 settings

@scenario("old-config-new-settings-default", "edge26", 60, "A config.yml with no carry-amount or find-* lines reloads without warnings, and the defaults apply (16 per trip, find on).", alone=True)
def _(c):
    text = open(CONFIG).read()
    stripped = re.sub(r"(?m)^(carry-amount|find-enabled|find-radius|find-highlight-seconds): .*\n", "", text)
    with open(CONFIG, "w") as f:
        f.write(stripped)
    reply = say(c, "cgc reload")
    c.chest("copper", 2, 10, [("birch_log", 32)], block="waxed_copper_chest")
    c.chest("logs", 14, 10, [("oak_log", 1)])
    c.golem(4, 10)
    most = [0]

    def check():
        most[0] = max(most[0], c.holding("birch_log"))
        warnings = [l for l in c.log_lines() if "WARN" in l and "CopperGolemClerk" in l]
        return [has(reply, "reloaded"), (not warnings, f"no plugin warnings ({warnings[:2]})"),
                (most[0] == 16, f"most held at once: {most[0]} (want 16)"),
                expect(c, "logs", "birch_log", 32), has(find_at(c, "birch_log", 14, 10), "32 birch log in 1 container")]
    return check


@scenario("find-settings-out-of-range", "edge26", 30, "find-radius: 500, find-highlight-seconds: -3 and find-highlight-count: 45 are warned about and clamped (64, 0 and 30).", alone=True)
def _(c):
    set_options(c, find_radius="500", find_highlight_seconds="-3", find_highlight_count="45")
    def check():
        log = "\n".join(c.log_lines())
        return [("find-radius must be 1 to 64 (it's 500); using 64" in log, "warned about find-radius"),
                ("find-highlight-seconds must be 0 to 120 (it's -3); using 0" in log, "warned about find-highlight-seconds"),
                ("find-highlight-count must be 0 to 30 (it's 45); using 30" in log, "warned about find-highlight-count"),
                has(find_at(c, "dirt"), "within 64 blocks")]
    return check


@scenario("find-many-shulkers-time", "edge26", 90, "/cgc find in a storage room of 200 chests, each full of shulker boxes full of items, answers in under a second.", alone=True)
def _(c):
    import time
    box = 'shulker_box[minecraft:container=[' + ",".join(
        f'{{slot:{i},item:{{id:"minecraft:cobblestone",count:64}}}}' for i in range(18)) + ']]'  # 18: RCON caps a command at ~1446 bytes
    x0, y0, z0 = c.abs(1, 1)
    # 200 chests: 10 x 20 on the floor and stacked once, each with 27 full shulker boxes.
    for dx in range(0, 20, 2):
        for dz in range(0, 20, 2):
            for dy in (0, 1):
                x, y, z = x0 + dx, y0 + dy, z0 + dz
                c.run(f"setblock {x} {y} {z} minecraft:chest[facing=south]")
                for slot in range(27):
                    c.run(f"item replace block {x} {y} {z} container.{slot} with minecraft:{box} 1")
    c.chest("target", 21 - 1, 20, [("diamond", 3)])
    times = []

    def check():
        start = time.time()
        reply = find_at(c, "diamond")
        times.append(time.time() - start)
        best = min(times)
        return [has(reply, "3 diamond in 1 container"), (best < 1.0, f"answered in {best:.2f}s (want under 1s)")]
    return check
