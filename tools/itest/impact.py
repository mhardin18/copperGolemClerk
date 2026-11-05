"""Which in-game tests to run for a change: the smoke set, and suites picked from changed files.

    run.py smoke              # about 3 minutes: one or two scenarios per feature
    run.py --changed          # smoke + the suites the branch's changes touch
    run.py --changed=<ref>    # ... compared with <ref> instead of the release branch
    run.py --changed --parallel-only   # leave out the scenarios that run one at a time (quick)

Keep SMOKE to scenarios that pass quickly; run.py refuses to start if a name here
no longer exists. Keep SOURCE_SUITES up to date when a class or suite is added.
"""
import os
import re
import subprocess

# One or two scenarios per feature. All but deliver-row4 run in parallel.
SMOKE = [
    # sorting, labels, overflow, returns
    "group-match", "frame-label", "far-half-sign", "overflow-before-empty", "full-chest-overflow",
    "fetch-from-overflow", "return-homeless", "locked-chest", "label-plus-overflow",
    "unreachable-home-no-ping-pong", "label-overflow-no-loop", "fetch-no-path", "waterlogged-chest",
    "filter-only-logs", "trapped-chest-and-barrel", "partial-delivery", "nether-group-match",
    # the world changing mid-task
    "target-destroyed", "overflow-gone-during-fetch", "golem-killed-carrying", "chunk-unloaded-mid-trip",
    # reach
    "check-reach", "take-row4-default", "deliver-row4",
    # furnaces
    "furnace-feed-from-supply-copper-chest", "furnace-fetch-from-supply-chest", "fuel-just-enough",
    "furnace-empty", "take-out-when-done", "lava-bucket-waits-in-fuel", "edge30-supply-barrel",
    "overflow-no-hopper-fetched", "overflow-hopper-above-waits-for-8",
    # golems: modes, traffic, greetings, rescue, labels, deaths, tidying
    "vanilla-mode-sorts-like-vanilla", "mode-markers", "give-way-niche", "greet-iron-golem",
    "greet-not-while-sorting", "rescue-home-learned", "labels-hidden-normally", "death-log-kill",
    "tidy-merge-stacks", "other-plugin-forces-accept",
    # commands
    "check-labels", "overflow-report", "help", "filter-check-command", "check-furnace-report",
    "golems-lists-loaded", "find-item", "find-group", "alert-overflow-full",
]

# Source file (class name, or a path prefix) -> suites that exercise it.
SORTING = ["core", "edge", "setup", "world", "dimensions"]
FURNACE = ["furnace", "furnace-edge", "furnace-refill", "furnace-trips", "lava", "edge30", "work-order"]
SOURCE_SUITES = {
    "Alerts": ["alerts"],
    "ChestLabels": ["core", "edge", "filters", "edge26"],
    "ChestMatcher": ["core", "edge", "filters", "destinations"],
    "ChestReports": ["commands", "filters", "reach", "lava"],
    "ChestTidier": ["tidy"],
    "Claims": ["claims", "find", "limits"],
    "ConfigUpgrade": ["config"],
    # Settings live here too: options and config are added when the diff reads settings (see suites_for).
    "CopperGolemClerk": ["lifecycle", "commands"],
    "FurnaceFeeder": FURNACE,
    "GolemCarry": ["carry", "edge26"],
    "GolemDeaths": ["world", "modes"],
    "GolemFinder": ["finder"],
    "GolemGreetings": ["greet"],
    "GolemLabels": ["labels"],
    "GolemLimits": ["limits"],
    "GolemModes": ["modes"],
    "GolemPaths": ["setup", "distance"],
    "GolemPortals": ["portals", "dimensions"],
    "GolemRescue": ["rescue"],
    "GolemStats": ["stats"],
    "GolemTraffic": ["traffic"],
    "HelpText": ["commands"],
    "HomeFinder": SORTING + ["destinations", "reach", "overflow-hopper"] + ["distance"],
    "HopperLines": ["overflow-hopper", "furnace", "edge"],
    "ItemFinder": ["find"],
    "ItemGroups": ["core", "edge", "filters", "find"],
    "LabelAdvisor": ["commands", "find"],
    "OverflowFetcher": SORTING + ["overflow-hopper", "destinations", "reach"] + ["distance", "work-order"],
    "PaperSupport": [],
    "Reach": ["reach", "destinations", "edge30"],
    "ReachDelivery": ["reach", "destinations", "edge30"] + ["distance"],
    "Returns": ["core", "edge", "world", "edge26"],
    "SortListener": SORTING + ["reach", "destinations", "conflicts"] + ["distance", "work-order"],
    "StorageReport": ["commands"],
    "UpdateChecker": ["commands"],
    "WalkClock": FURNACE + ["reach", "destinations", "overflow-hopper", "distance"],
    "Versions": ["commands"],
    "src/main/resources/config.yml": ["options", "config", "distance"],
    "src/main/resources/plugin.yml": ["commands"],
    "tools/itest/hooks/": ["conflicts"],
}
# Changes that need no in-game tests of their own (the smoke set still runs).
NO_SUITES = (".md", "LICENSE", ".github/", "docs/", "src/test/", "tools/board/", ".gitignore")
# The harness itself: smoke is enough to show it still works.
HARNESS = ("tools/itest/arena.py", "tools/itest/run.py", "tools/itest/rcon.py", "tools/itest/impact.py",
           "tools/itest/README.md", "build.gradle.kts", "settings.gradle.kts", "gradle/", "gradlew")

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))


def git(*args):
    return subprocess.run(["git", "-C", ROOT, *args], capture_output=True, text=True).stdout


def default_base():
    """The release branch this version goes into (origin/release/<version>), else origin/main."""
    m = re.search(r'^version\s*=\s*"([^"]+)"', open(os.path.join(ROOT, "build.gradle.kts")).read(), re.M)
    for ref in ([f"origin/release/{m.group(1)}"] if m else []) + ["origin/main"]:
        if git("rev-parse", "--verify", "--quiet", ref).strip():
            return ref
    return "HEAD"


def changed_files(base):
    """Files changed on this branch since it left base, plus uncommitted and new files."""
    since = git("merge-base", base, "HEAD").strip() or base
    files = set(git("diff", "--name-only", since, "HEAD").split())
    files |= set(git("diff", "--name-only", "HEAD").split())
    files |= {line[3:] for line in git("status", "--porcelain").splitlines() if line.startswith("??")}
    return sorted(files)


def changed_scenarios(files, base):
    """Names of scenarios this branch adds or edits (any changed line inside one): they always run."""
    since = git("merge-base", base, "HEAD").strip() or base
    names = set()
    for f in files:
        if not re.match(r"tools/itest/suite_\w+\.py$", f):
            continue
        try:
            lines = open(os.path.join(ROOT, f)).read().splitlines()
        except OSError:
            continue
        starts = [(n, m.group(1)) for n, line in enumerate(lines, 1)
                  for m in [re.match(r'@scenario\(\s*"([^"]+)"', line)] if m]
        diff = git("diff", "-U0", since, "--", f) + git("diff", "-U0", "HEAD", "--", f)
        on_base = subprocess.run(["git", "-C", ROOT, "cat-file", "-e", f"{since}:{f}"], capture_output=True).returncode == 0
        if not on_base:
            names.update(name for _, name in starts)  # a file new on this branch: all of it
            continue
        for m in re.finditer(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", diff, re.M):
            first, count = int(m.group(1)), int(m.group(2) or 1)
            for line in range(first, first + max(count, 1)):
                before = [name for n, name in starts if n <= line]
                if before:
                    names.add(before[-1])
    return names


def suites_in(test_file):
    """The suites a suite_*.py file defines scenarios for."""
    try:
        text = open(os.path.join(ROOT, test_file)).read()
    except OSError:
        return []
    return sorted(set(re.findall(r'@scenario\(\s*"[^"]+",\s*"([^"]+)"', text)))


def comments_only(path, base):
    """True if every changed line of this file (since base, and uncommitted) is a # comment or blank."""
    since = git("merge-base", base, "HEAD").strip() or base
    diff = git("diff", "-U0", since, "--", path)
    lines = [l[1:] for l in diff.splitlines()
             if l[:1] in "+-" and not l.startswith(("+++", "---"))]
    return all(not l.strip() or l.lstrip().startswith("#") for l in lines)


def reads_settings(path, base):
    """True if this file's changed lines read or check settings (config.get..., getConfig())."""
    since = git("merge-base", base, "HEAD").strip() or base
    diff = git("diff", "-U0", since, "--", path)
    changed = [l for l in diff.splitlines() if l[:1] in "+-" and not l.startswith(("+++", "---"))]
    return any(re.search(r"\bconfig\.(get|is|contains)|getConfig\(\)|ConfigUpgrade", l) for l in changed)


def suites_for(files, base=None):
    """(suites, notes): the suites these changed files call for, and what was matched or not."""
    suites, notes = set(), []
    for f in files:
        name = os.path.splitext(os.path.basename(f))[0]
        if f == "src/main/resources/config.yml" and base and comments_only(f, base):
            notes.append(f"  {f}: comments only (ConfigDocsTest covers them); smoke only")
            continue
        if name == "CopperGolemClerk" and base and reads_settings(f, base):
            suites.update(["options", "config"])
            notes.append(f"  {f}: reads settings: options, config")
        if f.startswith("src/main/java/") and name in SOURCE_SUITES:
            picked = SOURCE_SUITES[name]
        elif any(f.startswith(k) or f == k for k in SOURCE_SUITES if "/" in k):
            picked = next(v for k, v in SOURCE_SUITES.items() if "/" in k and (f.startswith(k) or f == k))
        elif re.match(r"tools/itest/suite_\w+\.py$", f):
            picked = suites_in(f)
        elif f.endswith(NO_SUITES) or f.startswith(NO_SUITES) or f.startswith(HARNESS) or f in HARNESS:
            picked = []
        else:
            notes.append(f"  {f}: no suites known for it (add it to impact.SOURCE_SUITES); smoke only")
            continue
        suites.update(picked)
        notes.append(f"  {f}: {', '.join(picked) if picked else 'smoke only'}")
    return sorted(suites), notes
