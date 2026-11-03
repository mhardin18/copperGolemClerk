# In-game scenario tests

`run.py` tests CopperGolemClerk on the pluginDev server over RCON. Each scenario gets
its own walled, glowstone-lit platform at x=-500, y=150 (80 blocks apart along z, so
golems in different scenarios can't see each other's chests). Chunks are force-loaded,
so no player needs to be nearby. The runner places chests, signs, frames and items,
summons copper golems, polls chest contents until every check passes or time runs out,
then removes everything and the force-loads.

    python3 -I tools/itest/run.py smoke               # quick sanity check, ~10 min
    python3 -I tools/itest/run.py --changed --parallel-only   # smoke + what this branch touches, ~15 min
    python3 -I tools/itest/run.py --changed           # the same with the one-at-a-time scenarios, ~1-1.5 h
    python3 -I tools/itest/run.py                     # everything except manual scenarios (2-3 h)
    python3 -I tools/itest/run.py core world          # suites or scenario names (substring match)
    python3 -I tools/itest/run.py server-restart      # manual: asks you to restart the server
    python3 -I tools/itest/run.py soak                # manual: ~15-minute soak test
    python3 -I tools/itest/run.py smelter-replica     # manual: 6 minutes of a copied smelter; prints trips and idle furnaces
    python3 -I tools/itest/run.py --list              # what each scenario checks
    python3 -I tools/itest/run.py --keep              # leave the arenas up to inspect

## Running tests yourself

`tools/itest/run-tests.sh` does the whole thing in one command: builds the plugin from
the checkout it's in, deploys it to pluginDev, starts (or restarts) the server, runs the
tests, and stops the server again if it was stopped before.

    tools/itest/run-tests.sh smoke        # ~3 min
    tools/itest/run-tests.sh quick        # smoke + what this branch changes (--changed --parallel-only)
    tools/itest/run-tests.sh changed      # --changed
    tools/itest/run-tests.sh full         # everything except manual scenarios
    tools/itest/run-tests.sh core reach   # suites or scenario names
    tools/itest/run-tests.sh --no-deploy smoke   # test the jar pluginDev already has

To keep a long run going after you log out, start it with nohup (or inside tmux):

    nohup tools/itest/run-tests.sh full > /dev/null 2>&1 &

## Results

Every `run.py` run saves its results in `~/claude/itest-results/<date>-<time>-<what>/`
(`ITEST_RESULTS` overrides the folder); `~/claude/itest-results/latest` points at the
newest. `output.txt` is everything the run printed. `summary.json` has the status
(`running`, `finished` or `interrupted`), when it ran, the arguments, the git branch and
commit (and whether it had uncommitted changes), the plugin jar's file name and sha256,
the Paper build, totals per status, and every scenario with its status (`PASS`, `FAIL`,
`SKIP`, or `NOT RUN` when the run was stopped first), seconds, checks and, for
failures, the plugin's log lines for its platform. A stopped run (Ctrl-C or SIGTERM)
still restores `config.yml` and saves what finished.

## Which tests to run

| When | Command | Time |
|---|---|---|
| After every change | `run.py smoke` | ~10 min |
| Before opening a PR | `run.py --changed --parallel-only` | ~15 min |
| Before merging a PR that touches sorting code | `run.py --changed` | up to ~1.5 h |
| Before a release | `run.py` (full) | 2-3 h |

`smoke` is the list `impact.SMOKE`: one or two quick scenarios per feature, almost all
run in parallel. `--changed` compares the branch with its release branch
(`origin/release/<version>`, or `--changed=<ref>`), maps each changed file to the
suites that exercise it (`impact.SOURCE_SUITES`), and runs those plus the smoke set
and every scenario the branch adds or edits. A `config.yml` change that only touches
comments maps to nothing (ConfigDocsTest covers it); `CopperGolemClerk.java` maps to
the options and config suites only when the change reads settings. Files with no
mapping are listed so the table can be extended. `--parallel-only` leaves out the
scenarios that run one at a time (they change server-wide settings, so they can't
share the server), except ones the branch edits. Add `--list` to any of these to see
what would run without running it. Unknown options are refused (an unknown one used
to start a full run), and a stopped run (SIGTERM) still restores `config.yml`.

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
| commands | `/cgc check` on chests, `/cgc overflow`, `disabled-worlds` |
| config | broken configs: unknown entries, bad values, YAML errors, minimal config, console commands |
| tidy | golems tidy chests they deliver to (while the lid is open): stacks merged, like items together, option off |
| options | each config switch changes what golems do |
| lifecycle | reloads mid-task, reload spam; manual server restart |
| perf | hundreds of chests in range, many golems: tick time stays healthy |
| dimensions | sorting, overflow, fetching, returns, giving way and `/cgc check` in the Nether and the End |
| reach | chests stacked above vanilla's reach: delivering and taking with `deliver-reach` / `take-reach` raised, and at the default |
| greet | idle copper golems greeting iron and snow golems: cooldown, not while sorting, off, vanilla mode, unreachable, crowds, work interrupting a visit |
| limits | `max-golems-per-claim` / `max-golems-outside-claims`: the newest golem over a limit sorts like vanilla and the owner is told; under the limit, unclaimed land, vanilla-mode golems not counted, bad values |
| modes | golems switched to vanilla (sort like vanilla, no marker), antenna markers through switches, unloads and statues; iron and snow golems untouched |

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
