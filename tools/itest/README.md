# In-game scenario tests

This README is the one place the test instructions live: change it in the same PR as
anything that changes how tests are run, chosen or reported (`run.py`, `run-tests.sh`,
`impact.py`, `results.py`, suites, timings).

`run.py` tests CopperGolemClerk on the pluginDev server over RCON. Each scenario gets
its own walled, glowstone-lit platform at x=-500, y=150 (80 blocks apart along z, so
golems in different scenarios can't see each other's chests). Chunks are force-loaded,
so no player needs to be nearby. The runner places chests, signs, frames and items,
summons copper golems, polls chest contents until every check passes or time runs out,
then removes everything and the force-loads.

    python3 -I tools/itest/run.py smoke               # quick sanity check, ~3 min
    python3 -I tools/itest/run.py --changed --parallel-only   # smoke + what this branch touches, ~15 min
    python3 -I tools/itest/run.py --changed           # the same with the one-at-a-time scenarios, ~1-1.5 h
    python3 -I tools/itest/run.py                     # everything except manual scenarios (~1.5 h)
    python3 -I tools/itest/run.py core world          # suites or scenario names (substring match)
    python3 -I tools/itest/run.py server-restart      # manual: asks you to restart the server
    python3 -I tools/itest/run.py soak                # manual: ~15-minute soak test
    python3 -I tools/itest/run.py smelter-replica     # manual: 6 minutes of a copied smelter; prints trips and idle furnaces
    python3 -I tools/itest/run.py work-order-balance-0 work-order-balance-1 work-order-balance-3 \
        work-order-balance-6 work-order-balance-12   # manual, ~30 min: furnace-jobs-before-sorting at each value; prints log wait and furnace idle time
    python3 -I tools/itest/run.py --list              # what each scenario checks
    python3 -I tools/itest/run.py --keep              # leave the arenas up to inspect

## Running tests yourself

`tools/itest/run-tests.sh` does the whole thing in one command: builds the plugin from
the checkout it's in, deploys it to pluginDev, starts (or restarts) the server, waits for
it to answer, runs the tests, and stops the server again if it was stopped before. The
last line it prints says where the results were saved.

    tools/itest/run-tests.sh smoke        # ~3 min: one or two scenarios per feature
    tools/itest/run-tests.sh quick        # smoke + what this branch changes (--changed --parallel-only), ~15-35 min
    tools/itest/run-tests.sh changed      # the same with the one-at-a-time scenarios, up to ~1.5 h
    tools/itest/run-tests.sh full         # everything except manual scenarios, ~1.5 h
    tools/itest/run-tests.sh core reach   # suites or scenario names (any part of a name matches)
    tools/itest/run-tests.sh --no-deploy smoke   # test the jar pluginDev already has

It needs the `minecraft` group (to deploy and read pluginDev's RCON password) and
passwordless `sudo systemctl start|restart|stop mcd`.

Performance tests (what golems cost in server time, rather than what they do) are a separate
set: see [tools/perf/README.md](../perf/README.md). They use pluginDev too, so they can't run
alongside these.

### Before you start

- **Test the right code.** The script tests the checkout it lives in: for a feature
  branch, run it from that branch's working copy, not the release branch's.
- **Nobody should be on pluginDev**: the run restarts it.
- **One run at a time.** Two runs on the same server spoil each other's results.
- **Three starts per ten minutes.** pluginDev's service refuses a fourth start within ten
  minutes; if the script says it couldn't start or restart `mcd`, wait and run it again.
- The survival server isn't touched, but it shares the machine. A run stops itself if
  survival's tick time or the host's load gets too high.

### Long runs

Start it with nohup to keep it going after you log out (or run it inside tmux, which
lets you come back to it):

    nohup tools/itest/run-tests.sh full > /dev/null 2>&1 &

Watch it with `tail -f ~/claude/itest-results/latest/output.txt` (Ctrl-C stops watching,
not the run). To stop the run early:

    pkill -TERM -f 'tools/itest/run.py'

It then restores pluginDev's `config.yml`, saves the results of every scenario that
finished (the rest are `NOT RUN`), and the script stops pluginDev if it had started it.
Don't use `kill -9`: that skips all of this.

## Results

Every `run.py` run saves its results in `~/claude/itest-results/<date>-<time>-<what>/`
(`ITEST_RESULTS` overrides the folder); `~/claude/itest-results/latest` points at the
newest. `output.txt` is everything the run printed. `summary.json` has the status
(`running`, `finished` or `interrupted`), when it ran, the arguments, the git branch and
commit (and whether it had uncommitted changes), the plugin jar's file name and sha256,
the Paper build, totals per status, and every scenario with its status (`PASS`, `FAIL`,
`SKIP`, or `NOT RUN` when the run was stopped first), seconds, checks and, for
failures, the plugin's log lines for its platform. A stopped run (Ctrl-C or SIGTERM)
still restores `config.yml` and saves what finished. Older runs are kept.

A quick look from the shell:

    # the headline: status, totals, commit, jar
    jq '{status, totals, minutes, git, plugin_jar}' ~/claude/itest-results/latest/summary.json
    # just the failures, with their failed checks
    jq '.scenarios[] | select(.status=="FAIL") | {name, checks: [.checks[] | select(.ok|not) | .text]}' \
      ~/claude/itest-results/latest/summary.json

The end of `output.txt` has a plain summary line too, e.g. `412/414 passed, 0 skipped, in 168.3 min`.

To have Claude go through them, ask it to "read the latest test results": it reads
`latest/summary.json` (and `output.txt` if it needs more), checks which commit and jar
were tested, and says whether each failure is a plugin bug or a test-timing problem.

### When something goes wrong

| You see | What it means / what to do |
|---|---|
| `build failed` | The code doesn't compile or a unit test failed; nothing was deployed or tested. The Gradle output above it says which. |
| `couldn't start mcd` / `couldn't restart mcd` | The 3-starts-in-10-minutes limit: wait ten minutes and run again. |
| `pluginDev didn't answer RCON within 5 minutes` | The server didn't come up: see `/opt/minecraft/pluginDev/logs/latest.log`. |
| `unknown option(s)` | A typo in an option. Unknown options are refused (an unknown one used to start a full run). |
| A scenario fails in a big run but passes on its own | Probably timing under load: re-run it by name (`run-tests.sh <name>`). If it keeps failing, it's real. |
| `note: checking N scenario(s) took 25s this round` | One round of checks took longer than 10 seconds. Scenarios that act while a golem walks (break a chest, kill the golem) can miss their moment when rounds are that slow, and fail. Rounds used to take most of a minute in a full run because every scenario re-read the whole server log; that is fixed, so a note like this means something else is slow (RCON, or a very busy server). |
| The machine rebooted, or `kill -9` was used, mid-run | The results are cut off; pluginDev's `config.yml` may still have a test setting, and test golems and force-loaded chunks may be left around x=-500. Check the config against the shipped defaults and clear the leftovers; the next run that reuses a platform clears it too. |

## Which tests to run

| When | Command | Time |
|---|---|---|
| After every change | `run.py smoke` | ~3 min |
| Before opening a PR | `run.py --changed --parallel-only` | ~15-35 min |
| Before merging a PR that touches sorting code | `run.py --changed` | up to ~1.5 h |
| Before a release | `run.py` (full) | ~1.5 h |

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
| limits | `max-golems-per-claim` / `claim-blocks-per-extra-golem`: the newest golem over a limit sorts like vanilla and the owner is told; each claim on its own, admin claims, bigger claims get more, under the limit, unclaimed land not limited, vanilla-mode golems not counted, the removed `max-golems-outside-claims` warned about, bad values |
| work-order | what an idle golem does first: furnace upkeep before sorting, sorting before taking out finished items, `furnace-jobs-before-sorting` (1, and 0 for the old order); manual `work-order-balance-<n>` benchmarks for choosing its default |
| distance | `search-distance` above 32: golems finding, walking to and moving items for chests, copper chests, `[Overflow]` chests and furnaces past vanilla's range |
| lava | lava bucket fuel, a cauldron dripping lava refills, `furnace-lava-min-items`, and cauldron search range |
| furnace, furnace-edge, furnace-trips, furnace-refill | furnace feeding, fuelling and taking out, mid-job changes, fewer fuller trips, `furnace-refill-below` and `furnace-wait-seconds` |
| modes | golems switched to vanilla (sort like vanilla, no marker), antenna markers through switches, unloads and statues; iron and snow golems untouched |
| alerts | full-chest and filling-[Overflow] alerts: the chest and item named once, no alert when there's room, `full-chest-alerts` and `overflow-alert-percent` |
| carry | `carry-amount`: bigger loads per trip, stack-size caps, fetching from [Overflow], out-of-range values |
| claims | GriefPrevention claims: alerts go to the claim owner, lost-golem teleport by owner permission, `/cgc stats` counted per owner |
| destinations | barrels and shulker boxes as destinations (`destination-containers`): labels, contents, blocked lids, reach, full-container alerts |
| filters | `[Only <group>]` filters on copper chests: only matching items are taken, two filter lines, `/cgc check`, returns skip filtered chests |
| find | `/cgc find`: items, groups, patterns and tags, shulker contents, other containers, radius, a radius argument (and `find-max-radius` not limiting the console), the note on unloaded chunks, off switch, related-name hints |
| finder | `/cgc golems`: loaded and unloaded golems, last-seen spots, a radius argument (counted per axis, like find), killed golems forgotten, positions saved to `golems.yml` |
| labels | status labels over golems (bamboo in hand): what they say, off switch, removed with the golem, tick time with many |
| overflow-hopper | hopper-fed [Overflow] chests: golems wait for `overflow-fetch-min` or `overflow-fetch-wait-seconds`; chests no hopper feeds are emptied at once |
| portals | `avoid-portals`: golems stay out of Nether portals unless the option is off or they're in vanilla mode |
| rescue | lost or stuck golems (`rescue-lost-golems`): walking back to their copper chest, alerts, teleport home, adopting a new home |
| stats | `/cgc stats` counts deliveries and saves them to `stats.yml` |
| traffic | `golems-give-way`: golems meeting in corridors and doorways step aside or back out, avoiding fire, cobwebs and holes |
| edge26, edge30 | extra edge cases for `carry-amount` and `/cgc find` (edge26), and for furnaces, lava and hopper-fed [Overflow] chests (edge30) |

Scenarios that change something server-wide (config, reloads, tick timing) run one at a
time after the others, and the runner restores `config.yml` after each.

## Reading containers in a check

Count items with `c.count(name, item)` (it uses `execute if items`). To see one slot,
use `slot(c, name, n)` from `suite_furnace` (furnace slots: 0 input, 1 fuel, 2 output);
it asks for that slot alone with `data get block ... Items[{Slot:<n>b}]`. Don't parse a
whole `data get block ... Items`: the server cuts long output short (`{Slot:...`), so
anything after the first item or two goes missing. Until 3.1.0 `slot()` did that, and a
furnace with input in slot 0 always read as having no fuel.

## Checking the server log

Scenarios run side by side, so `c.log_lines()` holds every platform's lines. To react to
what this scenario's golem did, use `c.cell_lines()` (only lines with a coordinate on this
platform), or `logged(c, text)` from `suite_furnace_edge`, which uses it. Until 3.1.0
`logged()` read the whole log: one golem's "waits up to" set off the triggers of every
waiting scenario, which broke a supply chest or killed a golem before it had picked
anything up. `c.log_lines()` is fine for warnings from scenarios that run `alone`.

## Minecarts in a scenario

Minecarts ignore `NoGravity`. A cart summoned over a hole drops out of the arena within a
second, and the plugin never sees it. Put a block under it (and add that block to
`c.cleanup`), as `furnace-collected-by-hopper-minecart` does. Until 3.2.0 that scenario
removed the floor under the furnace and always failed.

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
