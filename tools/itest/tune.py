"""Tuning driver: runs batches of tuning layouts (suite_tuning) with different settings and
writes one JSON line per layout to a results file.

    python3 -I tools/itest/tune.py batches.json results.jsonl

batches.json: [{"label": "...", "settings": {"furnace-refill-below": "24"}, "scenarios": ["tune-big-5g", ...]}, ...]
Settings are set in pluginDev's config.yml before each batch (and put back after the last);
each key must already be in the file. While a batch runs, the server's tick time (mspt) and
the host's load are sampled once a minute. Only for pluginDev.
"""
import json
import os
import re
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import arena  # noqa: E402
from rcon import from_properties  # noqa: E402

CONFIG = arena.CONFIG


def set_settings(base, settings):
    text = base
    for key, value in settings.items():
        text, n = re.subn(rf"(?m)^{re.escape(key)}: .*$", f"{key}: {value}", text)
        if n != 1:
            raise RuntimeError(f"config has no single '{key}:' line")
    with open(CONFIG, "w") as f:
        f.write(text)


def host_load():
    with open("/proc/loadavg") as f:
        load1 = float(f.read().split()[0])
    mem = {}
    with open("/proc/meminfo") as f:
        for line in f:
            k, v = line.split(":")
            mem[k] = int(v.split()[0])
    return load1, round(mem["MemAvailable"] / 1024 / 1024, 1)


def watch(stop, samples):
    r = from_properties(os.path.join(arena.SERVER, "server.properties"))
    while not stop.is_set():
        try:
            out = re.sub(r"§.", "", r.cmd("mspt"))
            nums = [float(x) for x in re.findall(r"(\d+\.\d+)", out)]
            load1, mem = host_load()
            samples.append({"mspt": nums[:3], "load1": load1, "mem_free_gb": mem})
        except Exception as e:
            samples.append({"error": str(e)})
        stop.wait(60)


def main(batches_path, results_path):
    with open(batches_path) as f:
        batches = json.load(f)
    with open(CONFIG) as f:
        base = f.read()
    r = from_properties(os.path.join(arena.SERVER, "server.properties"))
    try:
        for i, batch in enumerate(batches):
            set_settings(base, batch.get("settings", {}))
            reply = re.sub(r"§.", "", r.cmd("coppergolemclerk reload"))
            if "reloaded" not in reply:
                raise RuntimeError(f"reload failed: {reply}")
            print(f"[{time.strftime('%H:%M:%S')}] batch {i + 1}/{len(batches)} {batch['label']}: {', '.join(batch['scenarios'])}", flush=True)
            stop, samples = threading.Event(), []
            t = threading.Thread(target=watch, args=(stop, samples), daemon=True)
            t.start()
            out = subprocess.run([sys.executable, "-I", os.path.join(HERE, "run.py")] + batch["scenarios"],
                                 capture_output=True, text=True).stdout
            stop.set()
            t.join(5)
            lines = [json.loads(l[l.index("TUNE ") + 5:]) for l in out.splitlines() if "TUNE {" in l]
            fails = [l for l in out.splitlines() if l.startswith("FAIL")]
            with open(results_path, "a") as f:
                for res in lines:
                    res.update({"batch": batch["label"], "settings": batch.get("settings", {}), "server": samples})
                    f.write(json.dumps(res) + "\n")
            print(f"    {len(lines)} result(s){', FAILED: ' + '; '.join(fails) if fails else ''}; server: {samples}", flush=True)
            if len(lines) < len(batch["scenarios"]):
                print(out[-3000:], flush=True)
    finally:
        with open(CONFIG, "w") as f:
            f.write(base)
        r.cmd("coppergolemclerk reload")
        print("Restored config.yml.", flush=True)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
