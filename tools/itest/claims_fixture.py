"""Set up the test claims for suite_claims on pluginDev (idempotent).

Writes two GriefPrevention claim files owned by made-up players, and grants one of them
coppergolemclerk.teleport in LuckPerms over RCON. GriefPrevention reads claims only at
start-up: restart pluginDev afterwards (sudo -n systemctl restart mcd).

Usage: python3 -I tools/itest/claims_fixture.py [--remove]
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from arena import SERVER  # noqa: E402
from rcon import from_properties  # noqa: E402

WORLD = "pluginDev_world"
CLAIMS = {
    "9001.yml": ("00000000-0000-4000-8000-0000000000a1", (-1100, -1100), (-1000, -1050)),
    "9002.yml": ("00000000-0000-4000-8000-0000000000b2", (-1100, -1040), (-1000, -990)),
}
DIR = SERVER + "/plugins/GriefPreventionData/ClaimData"


def main(argv):
    remove = "--remove" in argv
    for name, (owner, (x1, z1), (x2, z2)) in CLAIMS.items():
        path = os.path.join(DIR, name)
        if remove:
            if os.path.exists(path):
                os.remove(path)
            continue
        with open(path, "w") as f:
            f.write(f"Lesser Boundary Corner: {WORLD};{x1};-64;{z1}\n"
                    f"Greater Boundary Corner: {WORLD};{x2};319;{z2}\n"
                    f"Owner: {owner}\nBuilders: []\nContainers: []\nAccessors: []\nManagers: []\n"
                    f"Parent Claim ID: -1\ninheritNothing: false\n")
    next_id = os.path.join(DIR, "_nextClaimID")
    if not remove and os.path.exists(next_id) and int(open(next_id).read().strip() or 0) <= 9002:
        with open(next_id, "w") as f:
            f.write("9003")
    try:
        r = from_properties(SERVER + "/server.properties")
        owner_a = CLAIMS["9001.yml"][0]
        verb = "unset" if remove else "set"
        extra = "" if remove else " true"
        print(r.cmd(f"lp user {owner_a} permission {verb} coppergolemclerk.teleport{extra}"))
    except OSError as e:
        print(f"RCON not reachable ({e}); start pluginDev and run this again for the LuckPerms part")
    print("Claims", "removed" if remove else "written", "- restart pluginDev so GriefPrevention loads them.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
