"""claims: GriefPrevention claims and LuckPerms permissions, with two test claims.

The test claims (tools/itest/claims_fixture.py writes them; GriefPrevention loads claims at
start-up, so restart pluginDev after) belong to two made-up players who never join, so
everything here also covers owners who are offline:
- OWNER_A owns claim A and has coppergolemclerk.teleport (granted in LuckPerms by the fixture).
- OWNER_B owns claim B and doesn't.
Each scenario's platform sits inside one of the claims (place=...), so they run one at a time.
"""
import os
import re

from arena import SERVER, Skip, scenario
from suite_options import set_options
from suite_rescue import FAR, far_golems, lost_golem, walkway
from suite_alerts import alerts, full_logs_chest

OWNER_A = "00000000-0000-4000-8000-0000000000a1"
OWNER_B = "00000000-0000-4000-8000-0000000000b2"
# Claim A: x -1100..-1000, z -1100..-1050; claim B: x -1100..-1000, z -1040..-990 (all heights).
PLACE_A = (-1090, -1090)
PLACE_B = (-1090, -1030)
CLAIM_FILES = (SERVER + "/plugins/GriefPreventionData/ClaimData/9001.yml",
               SERVER + "/plugins/GriefPreventionData/ClaimData/9002.yml")


def need_fixture():
    if not all(os.path.exists(f) for f in CLAIM_FILES):
        raise Skip("test claims not set up: run tools/itest/claims_fixture.py, then restart pluginDev")


def boxed_lost_golem(c):
    """A golem 48 blocks east of its copper chest, walled in so it can't walk back."""
    c.chest("copper", 2, 10, block="waxed_copper_chest")
    walkway(c)
    x, y, z = c.abs(FAR, 10)
    c.run(f"fill {x - 1} {y} {z - 1} {x + 1} {y + 2} {z + 1} minecraft:glass hollow")
    c.run(f"setblock {x} {y} {z} minecraft:air")
    lost_golem(c, FAR, 10)


@scenario("claims-alert-to-owner", "claims", 150, "A full [logs] chest inside claim A: the alert goes to claim A's owner (offline, so it's kept for when they join), not to everyone.", alone=True, place=PLACE_A)
def _(c):
    need_fixture()
    full_logs_chest(c)

    def check():
        got = alerts(c, "is full")
        return [(len(got) >= 1, "an alert about the full chest"),
                (any(f"to {OWNER_A} (when they join)" in l for l in got), f"addressed to the claim owner; got: {got[:1]}")]
    return check


@scenario("claims-teleport-permitted", "claims", 180, "lost-golem-teleport-home on, golem's copper chest in claim A, whose (offline) owner has coppergolemclerk.teleport in LuckPerms: the stuck golem is teleported home.", alone=True, place=PLACE_A)
def _(c):
    need_fixture()
    set_options(c, lost_golem_teleport_home="true")
    boxed_lost_golem(c)
    return lambda: [(any("teleported it back" in l for l in alerts(c)), "alert says it was teleported back"),
                    (far_golems(c) == 0, "the golem left its box"), (c.golems() == 1, "it's on the platform")]


@scenario("claims-teleport-not-permitted", "claims", 180, "Same, but in claim B, whose owner lacks coppergolemclerk.teleport: the golem is NOT teleported, just reported.", alone=True, place=PLACE_B)
def _(c):
    need_fixture()
    set_options(c, lost_golem_teleport_home="true")
    boxed_lost_golem(c)
    lost = lambda: [l for l in alerts(c) if "copper golem" in l.lower()]
    return lambda: [(any("can't find a way back" in l or "is stuck" in l for l in lost()), "an alert says it's lost"),
                    (not any("teleported it back" in l for l in lost()), "not teleported"),
                    (far_golems(c) == 1, "the golem is still in its box")]


@scenario("claims-stats-by-owner", "claims", 150, "Logs sorted inside claim A count for claim A's owner in /cgc stats.", alone=True, place=PLACE_A)
def _(c):
    need_fixture()
    c.chest("copper", 2, 10, [("acacia_log", 16)], block="waxed_copper_chest")
    c.chest("logs", 10, 10, [("oak_log", 1)])
    c.golem(4, 10)

    def check():
        reply = re.sub(r"§.", "", c.r.cmd("cgc stats"))
        line = next((l for l in reply.splitlines() if "by claim owner" in l), "")
        return [(c.count("logs", "acacia_log") == 16, f"logs has {c.count('logs', 'acacia_log')} acacia logs (want 16)"),
                (OWNER_A[:8] in line, f"claim A's owner listed: '{line.strip()}'")]
    return check
