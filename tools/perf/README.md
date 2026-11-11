# Performance tests

These measure how much server time copper golems cost with Copper Golem Clerk, on pluginDev.

They are separate from the in-game tests in `tools/itest`. Those check what golems do;
these measure what it costs. Both use pluginDev, so they can't run at the same time.

```
tools/perf/run-perf.sh all                 # ~3.5 h: everything except the soak
tools/perf/run-perf.sh soak                # ~1 h
tools/perf/run-perf.sh --ref v3.0.0-paper26.3 golems --label 3.0.0   # an older version
python3 -I tools/perf/perf.py --estimate all   # what a run would do and how long, without running it
```

`run-perf.sh`:

1. Builds and deploys this tree's jar. With `--ref`, it builds that git ref instead and
   puts this tree's jar back afterwards.
2. Restarts pluginDev and waits for it to settle.
3. Runs `perf.py`.

`--no-deploy` uses the jar pluginDev already has. Options after the test names go to
`perf.py` (`--help` lists them).

To keep a run going after you log out:

```
nohup tools/perf/run-perf.sh all >/dev/null 2>&1 &
```

## CPU speed

With the normal `schedutil` setting, a light load keeps this CPU near 800 MHz. That makes
every tick slower and makes few golems look dearer than many.

`run-perf.sh` switches the CPU to full speed (`performance`) for the run and back
afterwards, even if the run fails. This affects every server on the machine while it lasts.

It needs this sudoers rule (`sudo visudo -f /etc/sudoers.d/cpupower-governor`):

```
mhardin ALL=(root) NOPASSWD: /usr/bin/cpupower frequency-set -g performance, /usr/bin/cpupower frequency-set -g schedutil
```

Without the rule, the run goes ahead at the normal setting. The summary shows which
setting was in use.

## The tests

| Test | What runs | Points | Time |
|---|---|---|---|
| `baseline` | the server with nothing built | 1 | 9 min |
| `golems` | 0, 20, 50, 100, 150 and 200 golems sorting, with the plugin and with it off for the world (`disabled-worlds`) | 12 | 1 h 45 min |
| `search` | 20 golems sorting among 1,849 chests they can't reach, out to 128 blocks: the plugin off, then `search-distance` 32, 64, 96, 128 | 5 | 50 min |
| `cauldrons` | 20 idle golems with an empty bucket in a `[Fuel]` chest, at `search-distance` 32 and 128, without and then with an empty cauldron in every chunk out to 128 blocks | 4 | 40 min |
| `chests` | the itest `perf` suite (`many-chests-tick-time`, `many-idle-golems-scan`): pass or fail | 1 | 11 min |
| `soak` | 100 golems sorting for 60 minutes: whether tick time or memory creeps up | 1 | 1 h |

`all` is the first five. The times are estimates (`--estimate` works them out for any
options). The first run also generates the chunks the perf area uses.

### The golems' work

Golems work in units on a glowstone floor at x 3000, z 3000, y 230, far from the itest
platforms. Each unit has:

- a waxed copper chest full of birch logs
- a hopper beside it, feeding into it
- a chest on the hopper

Golems take logs from copper chests and put them in the chests on the hoppers. The hoppers
put them back. The work never runs out and needs no commands while it's measured.

Golems get an endless, invisible Luck effect. It keeps Paper from slowing them down when no
player is near, as `arena.golem` does for the itests.

### The cauldron test

This is the worst case for the lava cauldron lookup (`CauldronIndex`). An empty bucket is
waiting in `[Fuel]` and there is no full cauldron. So:

- every idle golem looks for a cauldron on every upkeep check
- every chunk with a cauldron in it is scanned again every 5 seconds

## Each point

A point goes through these steps:

1. Reset: units refilled, golems killed and summoned again, settings set and reloaded.
2. Warm up for 60 seconds.
3. Sample every 5 seconds for 180 seconds.
4. Do it all again (`--reps 2`).

Each test loads the same chunks for all its points, so points within a test compare
fairly. `golems` loads only the units' area. `search` and `cauldrons` load everything out
to 144 blocks.

Each sample records:

- Paper's `/mspt` average over the last 5 seconds (the main number), and its maximum.
- The CPU's package temperature, its fastest core's clock speed, and the host's load. Idle
  cores drop to 800 MHz to save power. When the fastest core is slow, heat or power limits
  are holding the CPU back.
- The server process's resident memory and CPU use, read from the process because spark's output doesn't reach RCON.
- How many items the golems are holding, as a check that they're working.
- Survival's player count and tick time.

A point is marked **noisy** when more than a fifth of its samples had any of these:

- the CPU at 90 °C or more
- the fastest core below 3,300 MHz while the CPU was at 80 °C or more (at light load the
  CPU idles slow anyway)
- a player on survival
- survival over 20 ms per tick

This machine's CPU reaches 96 °C and slows to about 2.9 GHz under full load. Check the
notes before trusting a number, especially at 150 and 200 golems.

## Results

Results go in `~/claude/perf-results/<time>-<label>/`. `latest` links to the newest.

- `summary.md` has one row per point:
  - the median and 95th-percentile ms per tick
  - what the point adds over the test's own reference (0 golems for `golems`, the plugin
    off for `search`, no cauldrons at the same distance for `cauldrons`)
  - the cost per golem
  - the server's CPU use
  - the hottest the CPU got, and the lowest its fastest core's clock fell
  - any noise

  For `soak`, it shows how fast tick time and memory grew per hour.
- `results.json` has every sample, for anything the summary doesn't show.

`--compare <earlier results folder>` adds a table of the points both runs measured. It
marks a point **worse** when its median is more than 10% and 0.5 ms higher.

To compare two versions, run the same tests on each (for example `--ref v3.0.0-paper26.3`
and then this tree). Compare the second with the first. 3.0.0 has no `search-distance`, so
for it only the 32-block points mean anything.

## When it's interrupted

Ctrl-C or a failure still kills the golems, takes the filler chests and cauldrons away,
puts `config.yml` back and releases the force-loaded chunks.

After a reboot or `kill -9`:

1. Check pluginDev's `config.yml` (`disabled-worlds` and `search-distance` especially).
2. Run any short test to clear leftover golems, for example
   `perf.py baseline --reps 1 --warmup 1 --measure 5`.

Leftover filler chests and cauldrons are harmless while unloaded. `--clear` on a later run
removes the whole perf area.

## Tests for the tool

`python3 -I -m unittest discover -s tools/perf` checks the parts that don't need a server
(parsing, statistics, the layout, comparing and estimating).
