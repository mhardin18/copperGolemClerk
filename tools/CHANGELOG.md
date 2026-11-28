# Tools changelog

Changes to the developer tools in `tools/`: the in-game tests (`itest`), the performance
tests (`perf`) and the sorting board scripts (`board`). These tools aren't part of the plugin
jar. Plugin changes are in the main [CHANGELOG](../CHANGELOG.md).

Versions are the plugin release each change came out with.

## 3.3.0 (in progress)

### New
- `cushions`: a new suite for `sit-on-cushions`. An idle golem sits on a cushion 6 blocks away; three golems and two cushions give two seated golems; a seated golem gets up when logs land in its copper chest, sorts them and sits again; with an iron golem nearby it says hello before sitting, and with a short greeting cooldown a seated golem gets up to greet again; `sit-on-cushions: false` never sits, and turning it off stands the plugin's golem up but leaves one put on a cushion with `/ride`. The platform clean-up also removes cushions.
- `greet-not-another-floor` only fails a visit that starts below the terrace. A copper golem that wanders up the steps first is on the iron golem's floor and may say hello (it failed once that way).
- `tidy-enchanted-books`: a golem delivers a Mending book to a `[books]` chest of jumbled enchanted books, which end up A to Z by enchantment, lowest level first.
- Code coverage: `./gradlew test jacocoTestReport` (unit tests) and `jacocoItestReport` (the in-game tests, from the JaCoCo agent on pluginDev; see itest README, "Code coverage"). About 860 new unit tests took line coverage from 48% to 94% (branches 34% to 84%), with fake golems that have scripted pathfinders and fake GriefPrevention and LuckPerms classes. They found two bugs, both fixed: tab completion for `/cgc find` and `/cgc groups item` threw on Paper's `LEGACY_` materials, and an unreachable `find-enabled` check.
- `commands`: `storage-report` runs `/cgc storage` on a server for the first time (a full `[logs]` chest flagged FULL, `[Overflow]`, an empty chest and a copper chest's waiting items). In-game coverage showed `StorageReport` at 3%.
- `run.py` turns on `debug: true` in pluginDev's `config.yml` for the run (its checks read the debug lines) and puts the file back as it found it at the end, so pluginDev's config can stay at the shipped defaults. A dev server whose config had been replaced with one with `debug: false` failed 52 scenarios.
- `cauldrons`: `cauldron-nook-idle-keeps-away` rebuilds survival's dripstone lava farm (cauldrons sunk into the floor of a 1-high nook under pointed dripstone), throws an idle golem toward the lava until the guard turns it back, then sends it back over the cauldrons every 5 s as vanilla's wandering did: those paths must be refused. `cauldron-nook-carrying-not-held-back` is the same with the golem holding a stone: no path is refused while it holds it.
- GolemTestHooks: `/golemwalk x1 y1 z1 x2 y2 z2 tx ty tz` sends the copper golems in a box to a spot through Paper's pathfinder, the way vanilla's wandering does (coming from another plugin, it counts as the golem's own path).
- `config`: `groups-yaml-syntax-error` checks that a YAML mistake in `groups.yml` keeps the previous settings and groups (a settings change made alongside it doesn't apply either) and names the line; `groups-left-in-config` checks that groups put back into `config.yml` are ignored with a warning. The runner restores `groups.yml` as well as `config.yml` after each scenario, and `set_options(group_aliases=...)` edits `groups.yml`. `bad-group-entries`, `groups-report`, `missing-groups` and `config-name-warnings` edit `groups.yml`; `minimal-config` now loads an empty `config.yml`.
- `GroupsFileTest` and `GroupsFilePaperTest` check the move of an older `config.yml`'s groups and aliases into `groups.yml` (line for line, CRLF, settings after the groups, a broken `config.yml` left alone until fixed) and how `groups.yml` is reloaded. `GroupsDocPaperTest` reads the shipped `groups.yml`.
- `config`: `settings-report` checks that `/cgc settings` names an old default, a misspelt setting (with the real name), a retired setting and a load warning, leaves out item groups, and explains one setting.
- `lids`: a new suite for chest lids left open. Seven scenarios keep golems busy for 4 to 6 minutes (a sorting room, crowded chests, `[Overflow]` fetching, reach, furnaces, give-way in a corridor lined with chests, greetings) and fail if any lid stays open over 20 seconds, or if a golem is more than 3 blocks from a chest vanilla says it still has open (it was sent off mid-visit). `lids-greet-between-jobs` (and `-crowd`, with 4 golems) checks that a golem idle between jobs, looking into its empty copper chest, doesn't leave to greet while the lid is up; `lids-fetch-between-jobs` and `lids-furnace-between-jobs` check the same for being sent to fetch from `[Overflow]` or to refuel a furnace. Two probes: a golem killed with its lid open (the lid closes) and one frozen with NoAI (the lid stays open: how an interrupted visit leaves a lid up).
- GolemTestHooks: `/golemlids x1 y1 z1 x2 y2 z2` lists open lids in a box and the golems vanilla still counts as holding a chest open.
- `config`: `groups-report` checks that `/cgc groups` names a group entry that matches no item, an item put in a second group (and which group golems prefer), a built-in group the config has under its older name, and a changed group, and that it shows one group and one item.
- `gestures`: a new suite for the chest animation when the plugin moves the items itself. A reach delivery to row 4, an `[Overflow]` fetch, and a furnace job (take from `[Furnace Supply]`, put into the furnace) each pass when the move happened and the golem held the matching state (`DROPPING_ITEM` or `GETTING_ITEM`) for at least 35 of the 40 ticks it's held still. `gesture-vanilla-baseline` checks the recorder against a plain vanilla delivery.
- GolemTestHooks: `/golemstates x1 y1 z1 x2 y2 z2` records every copper golem's state in a box each tick and lists the stretches since the last call.
- `cauldrons`: a new suite for `avoid-cauldrons` and refilling empty buckets from sorting. Cauldrons are set into the floor (rim level with it), as in the survival room where a golem burnt in one. Walking: a row of water, powder snow or lava cauldrons with one gap (the golem is walked round through the gap and delivers), a row with no gap (it never steps in and doesn't cross), a golem thrown at lava cauldrons 10 times (unhurt), one dropped from 4 blocks over the middle of a ring of them (set down outside it), one put inside a water cauldron (climbs out), and two golems meeting in a corridor lined with lava cauldrons. Every poll counts golems standing in a filled cauldron; any sighting or death fails. Controls: `avoid-cauldrons: false` and a vanilla-mode golem both burn. Buckets: one bucket from `[Overflow]` (with and without a `[bucket]` chest, and with the `[bucket]` chest up a 4-high stack), three with one cauldron (one is filled, two stay) or with a dripstone cauldron (two wait in `[Fuel]`), one or two from a copper chest, and the cases that still sort them (no `[Fuel]` chest, `[Fuel]` full, only an empty cauldron, `furnace-feeding: false`). Three scenarios copy the survival hopper-fed smelter: one empty bucket goes round and comes back full, two fill both cauldrons, and with the only `[Fuel]` chest on the fuel hopper an empty bucket waits in `[Overflow]` for a dripstone cauldron.
- `GroupsDocPaperTest` keeps the built-in groups list in `GROUPS.md` in step with the shipped `config.yml` (the build fails when it's out of date; `UPDATE_GROUPS_DOC=1 ./gradlew test --tests '*GroupsDoc*'` rewrites it). `ConfigUpgradeTest` checks that an owner's groups and aliases, with comments, blank lines and commented-out lines, survive an upgrade line for line.
- `greet`: `greet-not-another-floor` puts the iron golem on a terrace 3 blocks up, with steps the copper golem could climb, and checks it isn't visited (greetings reach only 2 blocks up or down). `greet-no-way-back` puts the copper golem on a fenced 2-high ledge whose only way down is a 2-block drop, and checks it doesn't set off for the iron golem below (it couldn't climb back to work). `greet-way-back-by-steps` adds 1-block steps, and the golem goes.
- `options`: `option-unlimited-fetchers` checks that `fetchers-per-overflow-chest: 0` (no limit) lets more than 4 golems fetch from one overflow chest at once.

### Changed
- `edge`: `fetcher-limit` expects at most 4 golems fetching from one overflow chest (the new default of `fetchers-per-overflow-chest`), not 2. It passed before only because pluginDev's old config still had 2.
- The `no_errors` check (`furnace-edge`, `lava`, `cauldrons` and others) skips the log lines `run.py` already treats as harmless. In a full run another platform summoning an item frame logged "Block-attached entity at invalid position", which failed every scenario running at the time.
- `settings-report` expects the summary's new pointer to `/cgc groups` for item groups.
- `impact.py` maps `SettingsReport` and `GroupsReport` to the `config` suite, `GolemGestures` to `gestures`, `reach` and `lids`, adds `gestures` for `FurnaceFeeder`, `OverflowFetcher` and `ReachDelivery`, and runs `lids` and `gestures` for changes to GolemTestHooks.
- `impact.py` maps the new `GolemCauldrons` to `cauldrons` and `traffic`, and adds `cauldrons` for `FurnaceFeeder`, `GolemTraffic`, `OverflowFetcher` and `SortListener`.
- `SortingPaperTest`'s return tests use a golem that keeps its state (MockBukkit's doesn't), and check that a return shows the golem putting the item in.
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
