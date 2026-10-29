#!/usr/bin/env python3
"""In-game scenario tests for CopperGolemClerk, run against pluginDev over RCON.

Each scenario gets its own walled, lit platform high in the sky at x=-500, 80 blocks
apart along z (golems search 32 blocks, so platforms can't interfere). Chunks are
force-loaded, so golems work with no player nearby. Scenarios that only touch their
own platform run in parallel; those that change something server-wide (config,
reloads, tick timing) run one at a time afterwards. Any unexpected error or
exception in the server log while a scenario runs fails it.

    python3 -I tools/itest/run.py                   # every suite except manual scenarios (~10 min)
    python3 -I tools/itest/run.py core world        # suites or scenario names (substring match)
    python3 -I tools/itest/run.py server-restart    # manual: asks you to restart the server
    python3 -I tools/itest/run.py --list
    python3 -I tools/itest/run.py --keep            # leave the arenas standing to inspect

Suites: core, world, setup, edge, scenes, conflicts, config, options, lifecycle, perf.
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import arena  # noqa: E402
import suite_commands  # noqa: E402,F401
import suite_config  # noqa: E402
import suite_conflicts  # noqa: E402,F401
import suite_core  # noqa: E402,F401
import suite_edge  # noqa: E402,F401
import suite_finder  # noqa: E402,F401
import suite_greet  # noqa: E402,F401
import suite_lifecycle  # noqa: E402,F401
import suite_modes  # noqa: E402,F401
import suite_options  # noqa: E402,F401
import suite_perf  # noqa: E402,F401
import suite_scenes  # noqa: E402,F401
import suite_reach  # noqa: E402,F401
import suite_setup  # noqa: E402,F401
import suite_tidy  # noqa: E402,F401
import suite_traffic  # noqa: E402,F401
import suite_world  # noqa: E402,F401
import suite_dimensions  # noqa: E402,F401  (last: its platforms come after the others)
from rcon import from_properties  # noqa: E402

POLL = 3


def select(argv):
    words = [a for a in argv if not a.startswith("--")]
    picked = []
    for i, s in enumerate(arena.SCENARIOS):
        named = any(w == s["suite"] or w in s["name"] for w in words)
        if (named or not words) and not (s["manual"] and not any(w in s["name"] for w in words)):
            picked.append((i, s))
    return picked


def unexpected_errors(lines, allowed):
    out = []
    for l in lines:
        if ("ERROR" in l or "Exception" in l or "SEVERE" in l) and not any(k in l for k in arena.KNOWN_HARMLESS + tuple(allowed)):
            out.append(l)
    return out


def run_batch(r, log, batch, keep):
    """Build a batch of scenarios, poll until each passes or times out. Returns results."""
    cells, checks, done, last = {}, {}, {}, None
    for i, s in batch:
        cells[i] = arena.Cell(r, i, log, s["dimension"])
        cells[i].forceload(True)
    time.sleep(2)
    try:
        for i, s in batch:
            c = cells[i]
            c.clear()
            c.build_floor()
            c.started, c.log_start = time.time(), log.size()
            try:
                checks[i] = s["build"](c)
            except arena.Skip as e:
                done[i] = ("SKIP", 0, [(True, str(e))])
            except Exception as e:  # a broken setup fails that scenario, not the whole run
                done[i] = ("FAIL", c.elapsed(), [(False, f"setup failed: {e}")])
        while len(done) < len(batch):
            time.sleep(POLL)
            for i, s in batch:
                if i in done:
                    continue
                c = cells[i]
                try:
                    results = checks[i]()
                except Exception as e:
                    results = [(False, f"check failed: {type(e).__name__}: {e}")]
                errors = unexpected_errors(c.log_lines(), s["allow_errors"])
                if errors:
                    results = results + [(False, f"server error during the scenario: {errors[0][:200]}")]
                    done[i] = ("FAIL", c.elapsed(), results)
                elif all(ok for ok, _ in results):
                    done[i] = ("PASS", c.elapsed(), results)
                elif c.elapsed() > s["timeout"]:
                    done[i] = ("FAIL", c.elapsed(), results)
            left = [s["name"] for i, s in batch if i not in done]
            if left and len(left) != last:
                print(f"  {len(batch) - len(left)}/{len(batch)} finished; waiting on {', '.join(left)}", flush=True)
                last = len(left)
    finally:
        if not keep:
            for c in cells.values():
                c.clear()
                c.forceload(False)
    return cells, done


def main(argv):
    if "--list" in argv:
        for s in arena.SCENARIOS:
            flag = " (manual)" if s["manual"] else " (alone)" if s["alone"] else ""
            print(f"{s['suite']:10} {s['name']:28} {s['timeout']:>4}s  {s['description']}{flag}")
        return 0
    keep = "--keep" in argv
    chosen = select(argv)
    if not chosen:
        print("no scenarios match", argv)
        return 2

    r = from_properties(os.path.join(arena.SERVER, "server.properties"))
    log = arena.Log(os.path.join(arena.SERVER, "logs/latest.log"))
    config_before = open(arena.CONFIG).read()
    r.cmd("coppergolemclerk reload")  # start clean: clears "no home" marks from earlier runs

    parallel = [(i, s) for i, s in chosen if not s["alone"]]
    alone = [(i, s) for i, s in chosen if s["alone"]]
    results = {}
    started = time.time()
    try:
        if parallel:
            print(f"Running {len(parallel)} scenario(s) in parallel at x={arena.X0}, y={arena.Y} ...")
            cells, done = run_batch(r, log, parallel, keep)
            results.update({i: (cells[i], d) for i, d in done.items()})
        for i, s in alone:
            print(f"Running {s['name']} on its own ...")
            try:
                cells, done = run_batch(r, log, [(i, s)], keep)
                results.update({i: (cells[i], d) for i, d in done.items()})
            finally:
                if open(arena.CONFIG).read() != config_before:  # options/config tests change settings
                    with open(arena.CONFIG, "w") as f:
                        f.write(config_before)
                    r.cmd("coppergolemclerk reload")
    finally:
        if open(arena.CONFIG).read() != config_before:  # a config test was interrupted
            with open(arena.CONFIG, "w") as f:
                f.write(config_before)
            print("Restored config.yml.")
        r.cmd("coppergolemclerk reload")

    print()
    failed = 0
    for i, s in chosen:
        cell, (status, elapsed, checks) = results[i]
        print(f"{status}  {s['suite']:10} {s['name']:28} {elapsed:5.0f}s  {s['description']}")
        if status == "SKIP":
            print(f"        {checks[0][1]}")
        if status == "FAIL":
            failed += 1
            for ok, text in checks:
                print(f"        {'ok ' if ok else 'BAD'} {text}")
            near = [l for l in cell.log_lines()
                    if "CopperGolemClerk" in l and "trace golem" not in l and cell.in_cell(l)]
            for l in near[-8:]:
                print("        log:", l[l.find("]: ") + 3:][:170])
    minutes = (time.time() - started) / 60
    skipped = sum(1 for i, _ in chosen if results[i][1][0] == "SKIP")
    print(f"\n{len(chosen) - failed - skipped}/{len(chosen) - skipped} passed, {skipped} skipped, in {minutes:.1f} min" + ("  (arenas kept)" if keep else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
