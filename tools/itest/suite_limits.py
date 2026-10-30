"""limits: max-golems-per-claim and max-golems-outside-claims.

Golems are recounted every 5 seconds; the oldest keep the plugin, the newest over the limit
sort like vanilla, and the claim owner (or, outside claims, players with
coppergolemclerk.alerts) gets an alert. The claim scenarios use test claim A (see
suite_claims; its owner is offline, so the alert is logged "to <uuid> (when they join)").

How to tell a vanilla golem from a plugin one: birch logs, an empty chest nearer the copper
chest and a [logs] chest of oak logs further away. The plugin sends birch logs to the [logs]
chest (a label beats contents, so it still does once the empty chest holds birch logs);
vanilla puts them in the empty chest (birch isn't an exact match for oak).
"""
import re
import time

from arena import conserved, expect, scenario
from suite_claims import OWNER_A, PLACE_A, PLACE_C, PLACE_D, need_fixture
from suite_options import set_options

BIRCH = 64  # four trips of 16


def limit_alerts(c, text=""):
    return [l for l in c.log_lines() if "Alert" in l and "copper golems in" in l and text in l]


def sorting_room(c):
    c.chest("copper", 2, 10, [("birch_log", 16)] * (BIRCH // 16), block="waxed_copper_chest")
    c.chest("empty", 7, 10)
    c.chest("logs", 16, 10, [("oak_log", 1)])
    c.sign(16, 10, "[logs]")


def tagged_golem(c, dx, dz, tag):
    x, y, z = c.abs(dx, dz)
    c.run(f'summon minecraft:copper_golem {x + 0.5} {y} {z + 0.5} {{Tags:["{tag}"],'
          'active_effects:[{id:"minecraft:luck",duration:-1,show_particles:0b,show_icon:0b}]}')


def old_and_new_golem(c):
    """Two golems a few seconds apart in age: the first is the older one."""
    c.golem(4, 8)
    time.sleep(3)
    c.golem(4, 12)


def _count_golems(c):
    m = re.search(r"count: (\d+)", c.r.cmd("execute if entity @e[type=minecraft:copper_golem]"), re.I)
    return int(m.group(1)) if m else 0


def all_sorted(c):
    return [(c.count("copper", "birch_log") == 0, f"copper chest has {c.count('copper', 'birch_log')} birch logs left (want 0)"),
            (c.holding() == 0, f"golems holding {c.holding()} (want 0)"),
            conserved(c, "birch_log", BIRCH)]


@scenario("limit-claim-newest-vanilla", "limits", 240, "max-golems-per-claim: 1 with two golems in claim A: the owner is told, the older golem sorts birch logs to the oak logs and the newer one sorts like vanilla (into the empty chest); none lost.", alone=True, place=PLACE_A)
def _(c):
    need_fixture()
    set_options(c, max_golems_per_claim="1")
    sorting_room(c)
    old_and_new_golem(c)

    def check():
        got = limit_alerts(c)
        return [(any(f"to {OWNER_A} (when they join)" in l and "2 copper golems in your claim; only 1 use" in l for l in got),
                 f"alert to claim A's owner: 2 golems, only 1 uses the plugin; got: {got[:1]}"),
                (c.count("logs", "birch_log") > 0, f"logs has {c.count('logs', 'birch_log')} birch logs (the plugin golem; want some)"),
                (c.count("empty", "birch_log") > 0, f"empty has {c.count('empty', 'birch_log')} birch logs (the vanilla golem; want some)")] + all_sorted(c)
    return check


@scenario("limit-claim-under-limit", "limits", 180, "max-golems-per-claim: 2 with two golems in claim A: no alert, and every birch log goes to the oak logs.", alone=True, place=PLACE_A)
def _(c):
    need_fixture()
    set_options(c, max_golems_per_claim="2")
    sorting_room(c)
    old_and_new_golem(c)
    return lambda: [(not limit_alerts(c), f"no golem limit alert; got: {limit_alerts(c)[:1]}"),
                    expect(c, "logs", "birch_log", BIRCH), expect(c, "empty", "birch_log", 0)] + all_sorted(c)


@scenario("limit-each-claim-own-limit", "limits", 180, "max-golems-per-claim: 1 is per claim, not per owner: a golem in claim C and one in claim A (same owner) each use the plugin, no alert.", alone=True, place=PLACE_C)
def _(c):
    need_fixture()
    set_options(c, max_golems_per_claim="1")
    # An older golem standing in claim A (owner A's other claim), outside this platform.
    x, z = PLACE_A[0] + 30, PLACE_A[1] + 10
    y = 150
    c.r.cmd(f"forceload add {x} {z}")
    c.r.cmd(f"setblock {x} {y - 1} {z} minecraft:stone")
    c.r.cmd(f'summon minecraft:copper_golem {x + 0.5} {y} {z + 0.5} {{Tags:["limit_other_claim"],NoAI:1b}}')
    c.cleanup += ["kill @e[type=minecraft:copper_golem,tag=limit_other_claim]",
                  f"setblock {x} {y - 1} {z} minecraft:air", f"forceload remove {x} {z}"]
    time.sleep(3)
    sorting_room(c)
    c.golem(4, 10)

    def check():
        return [(c.elapsed() >= 15, f"watched {c.elapsed():.0f}s of 15s (three recounts)"),
                (not limit_alerts(c), f"no golem limit alert; got: {limit_alerts(c)[:1]}"),
                expect(c, "logs", "birch_log", BIRCH), expect(c, "empty", "birch_log", 0)] + all_sorted(c)
    return check


@scenario("limit-admin-claim", "limits", 240, "max-golems-per-claim: 1 in an admin claim (no owner) with two golems: it has its own limit, players with coppergolemclerk.alerts are told about 'the admin claim', and the newer golem sorts like vanilla.", alone=True, place=PLACE_D)
def _(c):
    need_fixture()
    set_options(c, max_golems_per_claim="1")
    sorting_room(c)
    old_and_new_golem(c)

    def check():
        got = limit_alerts(c, "2 copper golems in the admin claim at")
        return [(bool(got), f"alert about 2 golems in the admin claim; got: {limit_alerts(c)[:1]}"),
                (c.count("logs", "birch_log") > 0, f"logs has {c.count('logs', 'birch_log')} birch logs (want some)"),
                (c.count("empty", "birch_log") > 0, f"empty has {c.count('empty', 'birch_log')} birch logs (want some)")] + all_sorted(c)
    return check


@scenario("limit-outside-claims", "limits", 240, "max-golems-outside-claims set to the number of golems already on unclaimed land plus one, then two more golems: an alert names the world and the count, and the newer test golem sorts like vanilla; none lost.", alone=True)
def _(c):
    # Other loaded golems on unclaimed land in this world count too (and are older), so the
    # limit leaves room for exactly one of the two test golems.
    others = _count_golems(c)
    set_options(c, max_golems_outside_claims=str(others + 1))
    sorting_room(c)
    old_and_new_golem(c)
    want = f"There are {others + 2} copper golems in unclaimed land in pluginDev_world; only {others + 1} use"

    def check():
        got = limit_alerts(c, want)
        return [(bool(got), f"alert: '{want}'; got: {limit_alerts(c)[:1]}"),
                (c.count("logs", "birch_log") > 0, f"logs has {c.count('logs', 'birch_log')} birch logs (want some)"),
                (c.count("empty", "birch_log") > 0, f"empty has {c.count('empty', 'birch_log')} birch logs (want some)")] + all_sorted(c)
    return check


@scenario("limit-per-claim-ignores-unclaimed", "limits", 180, "max-golems-per-claim: 1 doesn't apply on unclaimed land: two golems there both use the plugin, no alert.", alone=True)
def _(c):
    set_options(c, max_golems_per_claim="1")
    sorting_room(c)
    old_and_new_golem(c)
    return lambda: [(not limit_alerts(c), f"no golem limit alert; got: {limit_alerts(c)[:1]}"),
                    expect(c, "logs", "birch_log", BIRCH), expect(c, "empty", "birch_log", 0)] + all_sorted(c)


@scenario("limit-vanilla-mode-not-counted", "limits", 180, "max-golems-per-claim: 1 in claim A with one golem switched to vanilla by a player and one plugin golem: the vanilla one doesn't count, so no alert and the plugin golem keeps sorting to the oak logs.", alone=True, place=PLACE_A)
def _(c):
    need_fixture()
    set_options(c, max_golems_per_claim="1")
    sorting_room(c)
    tagged_golem(c, 4, 8, "limit_vanilla")  # the older one, so it would keep the plugin if it counted
    c.run('execute as @e[type=minecraft:copper_golem,tag=limit_vanilla] run data merge entity @s '
          '{BukkitValues:{"coppergolemclerk:mode":"vanilla"}}')
    time.sleep(3)
    c.golem(4, 12)

    def check():
        return [(c.elapsed() >= 30, f"watched {c.elapsed():.0f}s of 30s (six recounts)"),
                (not limit_alerts(c), f"no golem limit alert; got: {limit_alerts(c)[:1]}"),
                (c.count("logs", "birch_log") > 0, f"logs has {c.count('logs', 'birch_log')} birch logs (the plugin golem; want some)")]
    return check


@scenario("limit-bad-value", "limits", 30, "max-golems-per-claim: -3 and max-golems-outside-claims: 999999 are out of range: warnings, and 0 (no limit) and 100000 are used.", alone=True)
def _(c):
    set_options(c, max_golems_per_claim="-3", max_golems_outside_claims="999999")
    lines = lambda: c.log_lines()
    return lambda: [(any("max-golems-per-claim must be 0 to 100000 (it's -3); using 0." in l for l in lines()),
                     "warned about max-golems-per-claim -3, using 0"),
                    (any("max-golems-outside-claims must be 0 to 100000 (it's 999999); using 100000." in l for l in lines()),
                     "warned about max-golems-outside-claims 999999, using 100000")]
