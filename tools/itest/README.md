# In-game scenario tests

`run.py` tests SmartCopperGolems on the pluginDev server over RCON. Each scenario gets
its own walled, glowstone-lit platform at x=-500, y=150 (80 blocks apart along z, so
golems in different scenarios can't see each other's chests). Chunks are force-loaded,
so no player needs to be nearby. The runner places chests, signs, frames and items,
summons a copper golem, polls chest contents until every check passes or time runs
out, then removes everything and the force-loads.

    python3 -I tools/itest/run.py                 # all scenarios (~3-4 minutes)
    python3 -I tools/itest/run.py fetch return    # only scenarios whose name contains these
    python3 -I tools/itest/run.py --list          # what each scenario checks
    python3 -I tools/itest/run.py --keep          # leave the arena up to inspect

Exit code 0 = all passed. On failure it prints each check and the plugin's debug log
lines for that scenario's area (pluginDev runs with `debug: true`). Deploy and restart
first (`./gradlew deploy`, `sudo systemctl restart mcd`); the runner reloads the
plugin's config itself, which also clears "no home" marks from earlier runs.

Reads `/opt/minecraft/pluginDev/server.properties` for the RCON port and password, so run
it as a user in the `minecraft` group. Standard library only.
