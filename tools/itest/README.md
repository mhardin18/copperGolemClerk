# In-game scenario tests

`run.py` tests SmartCopperGolems on the pluginDev server over RCON. Each scenario gets
its own walled, glowstone-lit platform at x=-500, y=150 (80 blocks apart along z, so
golems in different scenarios can't see each other's chests). Chunks are force-loaded,
so no player needs to be nearby. The runner places chests, signs, frames and items,
summons copper golems, polls chest contents until every check passes or time runs out,
then removes everything and the force-loads.

    python3 -I tools/itest/run.py                     # everything except manual scenarios (~15 min)
    python3 -I tools/itest/run.py core world          # suites or scenario names (substring match)
    python3 -I tools/itest/run.py server-restart      # manual: asks you to restart the server
    python3 -I tools/itest/run.py soak                # manual: ~15-minute soak test
    python3 -I tools/itest/run.py --list              # what each scenario checks
    python3 -I tools/itest/run.py --keep              # leave the arenas up to inspect

Exit code 0 = all passed. On failure it prints each check and the plugin's debug log
lines for that scenario's area (pluginDev runs with `debug: true`). Any unexpected
error or exception in the server log during a scenario fails it.

## Suites

| Suite | What it covers |
|---|---|
| core | groups, frame and sign labels, plurals, most-specific chest, overflow, returns, fetching, golems far from players |
| world | the world changing mid-task: chests or signs destroyed or edited, golem killed, chunk unloaded, double chest split |
| setup | blocked chests, trapped chests and barrels, enchanted items, partial deliveries, crowds |
| edge | locked chests, hoppers, shulker/bundle contents, odd sign text and placement, range limits, fetch limits |
| scenes | whole sorting rooms end to end; a manual 15-minute soak |
| conflicts | other plugins deciding where golems may put things (needs GolemTestHooks) |
| config | broken configs: unknown entries, bad values, YAML errors, minimal config, console commands |
| tidy | golems tidy chests they deliver to: stacks merged, like items together, option off |
| options | each config switch changes what golems do |
| lifecycle | reloads mid-task, reload spam; manual server restart |
| perf | hundreds of chests in range, many golems: tick time stays healthy |

Scenarios that change something server-wide (config, reloads, tick timing) run one at a
time after the others, and the runner restores `config.yml` after each.

## Golems near and far from players

Paper only runs a mob's AI in short bursts (about 100 ticks a minute) when no player is
within the activation range (`spigot.yml`, 32 blocks for golems) and it isn't moving.
Test golems get an invisible, endless Luck effect, which Paper treats as a reason to keep
a mob active, so they behave as if a player were nearby. `golem(dx, dz, awake=False)`
leaves that out; the `sleepy-golem-*` scenarios test the far-from-players case.

## GolemTestHooks

`hooks/` is a tiny test-only plugin that pretends to be another plugin deciding where
golems may put items, keyed on the block under a chest, and only inside the test arena
(x < -400, y >= 140). Build it with `./gradlew build` in `hooks/` and copy the jar to
pluginDev's plugins folder. Without it the `conflicts` suite is skipped.

## Unit tests

`./gradlew test` in the project root runs the JUnit tests (sign word matching, YAML error
summaries); they need no server.

Reads `/opt/minecraft/pluginDev/server.properties` for the RCON port and password, so run
it as a user in the `minecraft` group. Standard library only.
