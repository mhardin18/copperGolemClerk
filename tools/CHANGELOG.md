# Tools changelog

Changes to the developer tools in `tools/`: the in-game tests (`itest`), the performance
tests (`perf`) and the sorting board scripts (`board`). These tools aren't part of the plugin
jar. Plugin changes are in the main [CHANGELOG](../CHANGELOG.md).

Versions are the plugin release each change came out with.

## 3.3.0 (in progress)

### New
- `config`: `settings-report` checks that `/cgc settings` names an old default, a misspelt setting (with the real name), a retired setting and a load warning, leaves out item groups, and explains one setting.
- `lids`: a new suite for chest lids left open. Seven scenarios keep golems busy for 4 to 6 minutes (a sorting room, crowded chests, `[Overflow]` fetching, reach, furnaces, give-way in a corridor lined with chests, greetings) and fail if any lid stays open over 20 seconds. `lids-greet-after-drop` checks that a golem that has just dropped its item doesn't leave to greet while the lid is up. Two probes: a golem killed with its lid open (the lid closes) and one frozen with NoAI (the lid stays open: how an interrupted visit leaves a lid up).
- GolemTestHooks: `/golemlids x1 y1 z1 x2 y2 z2` lists open lids in a box and the golems vanilla still counts as holding a chest open.
- `options`: `option-unlimited-fetchers` checks that `fetchers-per-overflow-chest: 0` (no limit) lets more than 4 golems fetch from one overflow chest at once.

### Changed
- `bad-numbers` sets `fetchers-per-overflow-chest` to -1 instead of 0, since 0 now means no limit, and checks for the console warning.

## 3.2.0 (2026-11-10)

### New
- `greet`: `greet-stays-near-work` checks that a golem doesn't visit an iron golem that is within 16 blocks of it but more than 16 from its copper chest.
- `rescue`: `rescue-home-from-furnace-supply` checks that taking from a `[Furnace Supply]` container makes it the golem's home. `rescue-walks-back-to-furnace` checks that a lost golem walks back to its furnace. `rescue-no-home-seeks-work` checks that a golem with no home walks to a work place up to 64 blocks away.
- `edge`: `group-alias-signs` checks that `[mechanisms]` and `[cooking]` signs take the groups their `group-aliases` point to. `group-alias-warnings` checks the console warnings for an alias that points to a missing group, and for one that is already a group's name.
- `commands`: `check-says-more-specific-chest-wins` checks that `/cgc check` and `/cgc label` say golems take copper ingots from a `[*copper*]` chest to a nearby `[ingots and gems]` chest, and how to keep them. It also checks that a chest with no rival says nothing of the kind.

### Changed
- `greet-interrupted-by-work`: the iron golem now stands within 16 blocks of the copper chest, so the visit can start now that greetings stay near work.
- `greet`: `greet-other-dies-on-the-way` also checks that the end of the visit is logged and that the golem walks back to its copper chest. `greet-unreachable` looks for the new "stopped going to say hello" log line.
- `run.py` sets clear weather (`weather clear 1000000`) at the start of every run. Rain hurts snow golems and made `greet-snow-golem` fail once.
- `work-order`: `take-out-after-sorting` is now `take-out-before-sorting`. It checks that the golem takes finished items out of the furnace before it takes any logs from the copper chest, then sorts them.
- Updated the `itest` scenarios for the optional `[radius]` on `/cgc find` and `/cgc golems`, for the furnace-output hopper minecart (#130), and for the new `/cgc find` chunk message.
- The `itest` test hooks plugin builds against Paper API 26.3 build 244.

### Fixed
- `furnace-collected-by-hopper-minecart` put the minecart over a hole, so it fell out of the arena and the test always failed. It now sits on a block.
- The music disc scenarios put one disc in each slot, so a stack can't hide a miscount.
- The `/cgc help` scenario expects the `[radius]` argument.

## 3.1.0 (2026-11-05)

### New
- New tool `tools/perf` (see its [README](perf/README.md)). It measures how much server time
  golems cost, with the plugin off and at each `search-distance`, and compares versions. It
  changes nothing in the plugin.
- `run-tests.sh` builds the plugin, deploys it to the dev server, starts or restarts the server, runs the tests and stops the server again if it was off. Modes are `smoke`, `quick`, `changed` and `full`, and `--no-deploy` reuses the jar already deployed.
- Every `run.py` run saves its results under `~/claude/itest-results/`, with `output.txt` and `summary.json` per run and a `latest` link. `ITEST_RESULTS` changes the folder.
- `run.py smoke` runs a short list of quick scenarios (`impact.SMOKE`) as a sanity check (#103).
- `run.py --changed[=ref]` runs the suites that exercise the files your branch changed, plus the smoke set and the scenarios the branch adds or edits. `--parallel-only` leaves out the one-at-a-time scenarios (#103).
- New suites `distance` (`search-distance`, including a value that isn't a multiple of 16) and `work-order` (furnace upkeep before sorting).
- Regression scenarios for 3.1.0 fixes.

### Changed
- `run.py` refuses unknown options. Before, `--help` started a full run.
- A stopped run (Ctrl-C or SIGTERM) still restores `config.yml`.
- Manual scenarios that a branch edits still run only when named.
- The `itest` README is now the one place for test instructions, and the separate runbook is folded into it.

### Fixed
- Many flaky scenarios made steadier (#102, #112). Mid-walk checks poll faster, carries are longer instead of using slow golems, and furnace and supply layouts are walled off from golem pathing.
- Tests survive blocks left over from earlier runs, count only the golems the limit counts, and read only the log lines from their own test platform.
- `tools/perf`: the run uses full CPU speed (performance governor) and restores it afterwards. It reads the server's count reply reliably and reports Paper's real version.

## 3.0.0 (2026-11-02)

### New
- New suites `limits`, `furnace-edge`, `furnace-refill`, `furnace-trips`, `overflow-hopper`, `lava` and `edge30`.
- New tuning harness (`tune.py` and the `tuning` suite) that measures furnace behavior, including fuel still burning and wasted burn.
- `cfg_upgrade_test.py` tests config upgrades, including a config that can't be written.
- The `furnace` suite covers `[Fuel]` chests, fuel order, burn-out and the `/cgc check` report.

### Fixed
- Steadier scenarios for overflow fetch, furnace refill, the cauldron-emptied test and the walled supply.

## 2.6.2 (2026-10-31)

### Changed
- Updated the `find` scenarios for the `Also nearby` line of `/cgc find`.

## 2.6.0 (2026-10-30)

### New
- New suites `finder`, `alerts`, `rescue`, `portals`, `filters`, `labels`, `stats`, `destinations`, `carry`, `find`, `claims` and `edge26`.
- `claims_fixture.py` sets up test claims for the `claims` suite.
- Pinned scenarios (`place=`) run at a fixed spot in the arena.
- Perf scenarios for status labels (60 labelled golems) and destinations (363 barrels).

### Changed
- Timing-sensitive scenarios (`vanilla-switch-mid-sidestep` and four more) run on their own.

### Fixed
- The RCON client joins replies that span several packets, so long command output is no longer cut off.
- `find-many-shulkers-time` keeps each command under the RCON size limit.

## 2.5.0 (2026-10-28)

### New
- New suites `reach`, `modes` and `greet`.
- Tests only kill the iron and snow golems on their own test platform.

## 2.4.1 (2026-10-28)

### Changed
- Added a test that trapdoor avoidance survives a server restart.

## 2.1.0 (2026-10-26)

### New
- New suite `dimensions` tests golems in the Nether and the End.
- Test for a give-way side pocket with no floor.

## 1.3.0 (2026-10-25)

### New
- New suites `commands` and `traffic` (narrow corridors, wide corridors, doorways, long corridors).
- Tests for several labels on one sign line, hanging signs and a two-line sign that mixes a group and an item.

### Fixed
- The `config` tests wait for the server's async log before reading it.

## 1.2.0 (2026-10-25)

### New
- New suite `tidy`.

## 1.0.0 (2026-10-24)

### New
- New tool `tools/itest`. `run.py` places chests, signs and golems on a dev server over RCON, checks the results and cleans up. It takes suite or scenario names, `--list` and `--keep`. A small test hooks plugin supports it.
- Suites `core`, `world`, `setup`, `edge`, `options`, `config`, `conflicts`, `lifecycle`, `scenes` and `perf`. Any unexpected error in the server log fails a scenario.
- New tool `tools/board`: `classify.py` and `to_config.py` generate the item groups in `config.yml` from the Golem Sorting Board's categories. Output is deterministic, and raw ore blocks group with ores.
