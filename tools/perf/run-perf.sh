#!/bin/bash
# Performance tests on pluginDev: build, deploy, (re)start the server, run tools/perf/perf.py.
#   tools/perf/run-perf.sh all                       # ~3.5 h: baseline, golems, search, cauldrons, chests
#   tools/perf/run-perf.sh soak                      # ~1 h: 100 golems for 60 minutes
#   tools/perf/run-perf.sh golems --counts 0,100 --modes plugin --reps 1   # perf.py options pass through
#   tools/perf/run-perf.sh --no-deploy search        # the jar pluginDev already has
#   tools/perf/run-perf.sh --ref v3.0.0-paper26.3 golems --label 3.0.0
#                                                    # an older version, built from that tag; this
#                                                    # tree's jar is put back afterwards
#   python3 -I tools/perf/perf.py --estimate all     # what a run would do, and how long, without running it
# Results: ~/claude/perf-results/<time>-<label>/ (summary.md, results.json); latest -> the newest.
# Compare with an earlier run: add --compare ~/claude/perf-results/<that run>.
# It refuses to start while an itest run is going (same server). Like run-tests.sh, it needs
# the minecraft group and passwordless sudo systemctl start|restart|stop mcd.
# To keep it going after you log out:  nohup tools/perf/run-perf.sh all >/dev/null 2>&1 &
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
deploy=1
ref=""
case "${1:-}" in
    --no-deploy) deploy=0; shift ;;
    --ref) ref=${2:?--ref needs a git ref}; shift 2 ;;
esac
[ $# -gt 0 ] || { sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }

log() { echo "[$(date +%T)] $*"; }

if pgrep -f tools/itest/run.py >/dev/null; then
    log "an itest run is going on pluginDev: wait for it to finish"
    exit 1
fi

restart() {
    if systemctl is-active --quiet mcd; then
        log "restarting pluginDev onto the new jar"
        sudo -n /usr/bin/systemctl restart mcd || { log "couldn't restart mcd (3 starts per 10 minutes at most)"; return 1; }
    else
        log "starting pluginDev"
        sudo -n /usr/bin/systemctl start mcd || { log "couldn't start mcd (3 starts per 10 minutes at most)"; return 1; }
    fi
    log "waiting for pluginDev to come up"
    for _ in $(seq 1 60); do
        if python3 -I -c 'import sys; sys.path.insert(0, "tools/itest"); from rcon import from_properties; from_properties("/opt/minecraft/pluginDev/server.properties").cmd("list")' 2>/dev/null; then
            sleep 30  # let start-up work (world loading, BlueMap) settle before measuring
            return 0
        fi
        sleep 5
    done
    log "pluginDev didn't answer RCON within 5 minutes"
    return 1
}

was_running=0
systemctl is-active --quiet mcd && was_running=1
here="$(git rev-parse --abbrev-ref HEAD) @ $(git rev-parse --short HEAD)"
PERF_REF="jar as deployed"
if [ -n "$ref" ]; then
    tmp=$(mktemp -d "${TMPDIR:-/tmp}/cgc-perf-ref.XXXXXX")
    log "building $ref"
    git worktree add -q --detach "$tmp/src" "$ref" || { log "no such ref: $ref"; exit 1; }
    (cd "$tmp/src" && ./gradlew -q build -x test && ./gradlew -q deploy) || { log "build of $ref failed"; git worktree remove --force "$tmp/src"; exit 1; }
    git worktree remove --force "$tmp/src"
    rmdir "$tmp" 2>/dev/null
    PERF_REF="$ref @ $(git rev-parse --short "$ref")"
    restart || exit 1
elif [ $deploy = 1 ]; then
    log "building $here"
    ./gradlew -q build || { log "build failed (see above); nothing deployed or tested"; exit 1; }
    ./gradlew -q deploy || { log "deploy failed"; exit 1; }
    PERF_REF="$here"
    restart || exit 1
elif [ $was_running = 0 ]; then
    restart || exit 1
fi

log "running: perf.py $*"
PERF_REF="$PERF_REF" python3 -I tools/perf/perf.py "$@"
status=$?

if [ -n "$ref" ]; then
    log "putting this tree's jar ($here) back on pluginDev"
    ./gradlew -q build -x test && ./gradlew -q deploy && restart
fi
if [ $was_running = 0 ]; then
    log "stopping pluginDev (it was stopped before)"
    sudo -n /usr/bin/systemctl stop mcd
fi
log "done (exit $status). Results: $(readlink -f ~/claude/perf-results/latest 2>/dev/null)"
exit $status
