"""Saves every run's results where they can be read back later (by you, or by Claude).

Each run gets a folder in ~/claude/itest-results (set ITEST_RESULTS to change it), named
<date>-<time>-<what ran>, holding:
  output.txt    everything run.py printed, as it printed it
  summary.json  the run: when, what was asked for, the git commit and the plugin jar that
                was tested, the Paper build, then every scenario with its status, time and
                checks (the failed ones also with the plugin's log lines for their platform)
summary.json says "status": "running" while the run goes on, and "finished" or
"interrupted" (stopped part way: the scenarios that finished are listed) at the end.
~/claude/itest-results/latest points at the newest run's folder.
"""
import datetime
import glob
import hashlib
import json
import os
import re
import subprocess
import sys

RESULTS = os.environ.get("ITEST_RESULTS", os.path.expanduser("~/claude/itest-results"))
ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))


def _git(*args):
    try:
        return subprocess.run(["git", "-C", ROOT, *args], capture_output=True, text=True, timeout=10).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def _jar(server):
    jars = sorted(glob.glob(os.path.join(server, "plugins", "CopperGolemClerk-*.jar")))
    if not jars:
        return None
    with open(jars[-1], "rb") as f:
        sha = hashlib.sha256(f.read()).hexdigest()
    return {"file": os.path.basename(jars[-1]), "sha256": sha}


def _paper(server):
    try:
        with open(os.path.join(server, "logs", "latest.log"), errors="replace") as f:
            for line in f:
                m = re.search(r"This server is running Paper version (\S+)", line)
                if m:
                    return m.group(1)
    except OSError:
        pass
    return None


class _Tee:
    def __init__(self, *streams):
        self.streams = streams

    def write(self, text):
        for s in self.streams:
            s.write(text)
            s.flush()
        return len(text)

    def flush(self):
        for s in self.streams:
            s.flush()


class Recorder:
    def __init__(self, argv, server):
        stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        words = [a.lstrip("-").replace("=", "_").replace("/", "_") for a in argv if a not in ("--keep",)]
        label = re.sub(r"[^A-Za-z0-9_.-]+", "-", "-".join(words))[:60] or "full"
        self.dir = os.path.join(RESULTS, f"{stamp}-{label}")
        os.makedirs(self.dir, exist_ok=True)
        self.output = open(os.path.join(self.dir, "output.txt"), "w")
        sys.stdout = _Tee(sys.__stdout__, self.output)
        self.summary = {
            "status": "running",
            "started": datetime.datetime.now().isoformat(timespec="seconds"),
            "finished": None,
            "args": argv,
            "git": {"branch": _git("rev-parse", "--abbrev-ref", "HEAD"), "commit": _git("rev-parse", "--short", "HEAD"),
                    "dirty": bool(_git("status", "--porcelain", "--untracked-files=no"))},
            "plugin_jar": _jar(server),
            "paper": _paper(server),
            "totals": None,
            "scenarios": [],
        }
        self._write()
        latest = os.path.join(RESULTS, "latest")
        try:
            if os.path.islink(latest):
                os.remove(latest)
            os.symlink(self.dir, latest)
        except OSError:
            pass
        print(f"Results: {self.dir}")

    def _write(self):
        path = os.path.join(self.dir, "summary.json")
        with open(path + ".tmp", "w") as f:
            json.dump(self.summary, f, indent=2)
        os.replace(path + ".tmp", path)

    def finish(self, status, scenarios, minutes):
        self.summary["status"] = status
        self.summary["finished"] = datetime.datetime.now().isoformat(timespec="seconds")
        self.summary["minutes"] = round(minutes, 1)
        self.summary["scenarios"] = scenarios
        counts = {}
        for s in scenarios:
            counts[s["status"]] = counts.get(s["status"], 0) + 1
        self.summary["totals"] = counts
        self._write()
        sys.stdout = sys.__stdout__
        self.output.close()
