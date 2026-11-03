#!/usr/bin/env python3
"""In-game scenario tests for CopperGolemClerk, run against pluginDev over RCON.

Each scenario gets its own walled, lit platform high in the sky at x=-500, 80 blocks
apart along z (golems search 32 blocks, so platforms can't interfere). Chunks are
force-loaded, so golems work with no player nearby. Scenarios that only touch their
own platform run in parallel; those that change something server-wide (config,
reloads, tick timing) run one at a time afterwards. Any unexpected error or
exception in the server log while a scenario runs fails it.

    python3 -I tools/itest/run.py smoke             # quick sanity check, about 10 min (impact.SMOKE)
    python3 -I tools/itest/run.py --changed         # smoke + suites this branch's changes touch
    python3 -I tools/itest/run.py --changed=main    # ... compared with another ref
    python3 -I tools/itest/run.py                   # every suite except manual scenarios (hours)
    python3 -I tools/itest/run.py core world        # suites or scenario names (substring match)
    python3 -I tools/itest/run.py server-restart    # manual: asks you to restart the server
    python3 -I tools/itest/run.py --list            # also with smoke / --changed / names: what would run
    python3 -I tools/itest/run.py --keep            # leave the arenas standing to inspect

Suites: core, world, setup, edge, scenes, conflicts, config, options, lifecycle, perf.
"""
import os
import signal
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import arena  # noqa: E402
import impact  # noqa: E402
import results as saved  # noqa: E402
import suite_alerts  # noqa: E402,F401
import suite_commands  # noqa: E402,F401
import suite_config  # noqa: E402
import suite_conflicts  # noqa: E402,F401
import suite_core  # noqa: E402,F401
import suite_edge  # noqa: E402,F401
import suite_filters  # noqa: E402,F401
import suite_finder  # noqa: E402,F401
import suite_greet  # noqa: E402,F401
import suite_labels  # noqa: E402,F401
import suite_lifecycle  # noqa: E402,F401
import suite_modes  # noqa: E402,F401
import suite_options  # noqa: E402,F401
import suite_perf  # noqa: E402,F401
import suite_portals  # noqa: E402,F401
import suite_scenes  # noqa: E402,F401
import suite_reach  # noqa: E402,F401
import suite_rescue  # noqa: E402,F401
import suite_setup  # noqa: E402,F401
import suite_stats  # noqa: E402,F401
import suite_tidy  # noqa: E402,F401
import suite_traffic  # noqa: E402,F401
import suite_world  # noqa: E402,F401
import suite_destinations  # noqa: E402,F401
import suite_carry  # noqa: E402,F401
import suite_furnace  # noqa: E402,F401
import suite_furnace_edge  # noqa: E402,F401
import suite_furnace_refill  # noqa: E402,F401
import suite_furnace_trips  # noqa: E402,F401
import suite_overflow_hopper  # noqa: E402,F401
import suite_lava  # noqa: E402,F401
import suite_tuning  # noqa: E402,F401
import suite_edge30  # noqa: E402,F401
import suite_find  # noqa: E402,F401
import suite_edge26  # noqa: E402,F401
import suite_claims  # noqa: E402,F401
import suite_limits  # noqa: E402,F401
import suite_dimensions  # noqa: E402,F401  (last: its platforms come after the others)
from rcon import from_properties  # noqa: E402

POLL = 3


FLAGS = ("--list", "--keep", "--changed", "--parallel-only")


def select(argv):
    """Scenarios to run: suites or names (substring), "smoke", --changed[=ref], or everything."""
    words = [a for a in argv if not a.startswith("--")]
    smoke = set()
    if "smoke" in words:
        words.remove("smoke")
        smoke = set(impact.SMOKE)
    changed = next((a for a in argv if a == "--changed" or a.startswith("--changed=")), None)
    if changed:
        base = changed.split("=", 1)[1] if "=" in changed else impact.default_base()
        files = impact.changed_files(base)
        suites, notes = impact.suites_for(files, base)
        print(f"Changes since {base}: {len(files)} file(s)")
        print("\n".join(notes) if notes else "  (none)")
        print(f"Running the smoke set plus: {', '.join(suites) if suites else 'no extra suites'}")
        smoke = set(impact.SMOKE)
        edited = impact.changed_scenarios(files, base)
        if edited:
            print(f"Scenarios this branch adds or edits (always run): {', '.join(sorted(edited))}")
        smoke |= edited
        words += suites
    picked = []
    for i, s in enumerate(arena.SCENARIOS):
        if s["name"] in smoke:
            picked.append((i, s))
            continue
        named = any(w == s["suite"] or w in s["name"] for w in words)
        everything = not words and not smoke
        if (named or everything) and not (s["manual"] and not any(w in s["name"] for w in words)):
            if not ("--parallel-only" in argv and s["alone"]):
                picked.append((i, s))
    alone = [s for _, s in picked if s["alone"]]
    if changed or "--parallel-only" in argv:
        # Parallel scenarios finish together in about 12 minutes; each alone one takes about half its limit.
        print(f"{len(picked)} scenario(s), {len(alone)} run one at a time: about "
              f"{12 + sum(s['timeout'] for s in alone) // 120} minutes.")
    return picked


def check_args(argv):
    """An error message for flags run.py doesn't know (an unknown one used to start a full run), else None."""
    bad = [a for a in argv if a.startswith("-") and a.split("=", 1)[0] not in FLAGS]
    if bad:
        return f"unknown option(s): {' '.join(bad)}. Options: {', '.join(FLAGS)} (--changed=<git ref>)."
    names = {s["name"] for s in arena.SCENARIOS}
    missing = [n for n in impact.SMOKE if n not in names]
    if missing:
        return f"impact.SMOKE names scenarios that don't exist: {', '.join(missing)}"
    known = {s["suite"] for s in arena.SCENARIOS}
    unknown = sorted({x for v in impact.SOURCE_SUITES.values() for x in v} - known)
    if unknown:
        return f"impact.SOURCE_SUITES names suites that don't exist: {', '.join(unknown)}"
    return None


def unexpected_errors(lines, allowed):
    out = []
    for l in lines:
        if ("ERROR" in l or "Exception" in l or "SEVERE" in l) and not any(k in l for k in arena.KNOWN_HARMLESS + tuple(allowed)):
            out.append(l)
    return out


def run_batch(r, log, batch, keep, results=None):
    """Build a batch of scenarios, poll until each passes or times out. Returns results."""
    cells, checks, done, last = {}, {}, {}, None
    for i, s in batch:
        cells[i] = arena.Cell(r, i, log, s["dimension"], s.get("place"))
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
            if results is not None:  # so a run stopped part way still reports what finished
                results.update({i: (cells[i], d) for i, d in done.items()})
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
    problem = check_args(argv)
    if problem:
        print(problem)
        return 2
    # Being stopped (SIGTERM, e.g. a killed background run) unwinds like Ctrl-C, so the
    # finally blocks below still restore config.yml and reload.
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    if "--list" in argv:  # with smoke, --changed or names: just what that would run
        for _, s in select([a for a in argv if a != "--list"]):
            flag = " (manual)" if s["manual"] else " (alone)" if s["alone"] else ""
            print(f"{s['suite']:10} {s['name']:28} {s['timeout']:>4}s  {s['description']}{flag}")
        return 0
    keep = "--keep" in argv
    record = saved.Recorder(argv, arena.SERVER)  # output.txt + summary.json in ~/claude/itest-results
    chosen = select(argv)
    if not chosen:
        print("no scenarios match", argv)
        record.finish("finished", [], 0)
        return 2

    r = from_properties(os.path.join(arena.SERVER, "server.properties"))
    log = arena.Log(os.path.join(arena.SERVER, "logs/latest.log"))
    config_before = open(arena.CONFIG).read()
    r.cmd("coppergolemclerk reload")  # start clean: clears "no home" marks from earlier runs

    parallel = [(i, s) for i, s in chosen if not s["alone"]]
    alone = [(i, s) for i, s in chosen if s["alone"]]
    results = {}
    started = time.time()
    interrupted = False
    try:
        try:
            if parallel:
                print(f"Running {len(parallel)} scenario(s) in parallel at x={arena.X0}, y={arena.Y} ...")
                cells, done = run_batch(r, log, parallel, keep, results)
                results.update({i: (cells[i], d) for i, d in done.items()})
            for i, s in alone:
                print(f"Running {s['name']} on its own ...")
                try:
                    cells, done = run_batch(r, log, [(i, s)], keep, results)
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
    except KeyboardInterrupt:
        interrupted = True
        print("\nStopped: reporting the scenarios that finished.")

    print()
    failed = 0
    entries = []
    for i, s in chosen:
        if i not in results:
            entries.append({"name": s["name"], "suite": s["suite"], "status": "NOT RUN", "seconds": 0,
                            "description": s["description"], "checks": [], "log": []})
            continue
        cell, (status, elapsed, checks) = results[i]
        print(f"{status}  {s['suite']:10} {s['name']:28} {elapsed:5.0f}s  {s['description']}")
        near = []
        if status == "SKIP":
            print(f"        {checks[0][1]}")
        if status == "FAIL":
            failed += 1
            for ok, text in checks:
                print(f"        {'ok ' if ok else 'BAD'} {text}")
            near = [l[l.find("]: ") + 3:][:170] for l in cell.log_lines()
                    if "CopperGolemClerk" in l and "trace golem" not in l and cell.in_cell(l)][-8:]
            for l in near:
                print("        log:", l)
        entries.append({"name": s["name"], "suite": s["suite"], "status": status, "seconds": round(elapsed),
                        "description": s["description"], "checks": [{"ok": bool(ok), "text": text} for ok, text in checks],
                        "log": near})
    minutes = (time.time() - started) / 60
    ran = [e for e in entries if e["status"] != "NOT RUN"]
    skipped = sum(1 for e in ran if e["status"] == "SKIP")
    print(f"\n{len(ran) - failed - skipped}/{len(ran) - skipped} passed, {skipped} skipped, in {minutes:.1f} min"
          + ("  (arenas kept)" if keep else "")
          + (f"; stopped with {len(entries) - len(ran)} not run" if interrupted else ""))
    print(f"Results saved in {record.dir}")
    record.finish("interrupted" if interrupted else "finished", entries, minutes)
    return 1 if failed or interrupted else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
