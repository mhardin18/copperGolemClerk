#!/bin/bash
# Build the plugin from this checkout, put it on the pluginDev server, run in-game tests,
# and save the results where they can be read back (~/claude/itest-results/latest).
#
#   tools/itest/run-tests.sh smoke              # ~3 min: one or two scenarios per feature
#   tools/itest/run-tests.sh quick              # smoke + what this branch changes, ~15-35 min
#   tools/itest/run-tests.sh changed            # the same with the one-at-a-time scenarios, up to ~2.5 h
#   tools/itest/run-tests.sh full               # everything except manual scenarios, ~3 h
#   tools/itest/run-tests.sh core reach         # suites or scenario names, as run.py takes them
#   tools/itest/run-tests.sh --no-deploy smoke  # test the jar pluginDev already has
#
# Run it from anywhere; it works on the checkout it lives in. It needs the minecraft group
# (to deploy and read pluginDev's RCON password) and passwordless
# `sudo systemctl start|restart|stop mcd`. pluginDev is started if it was stopped and
# stopped again afterwards; if it was running, it's restarted onto the new jar and left on.
# To keep it going after you log out:  nohup tools/itest/run-tests.sh full >/dev/null 2>&1 &
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
deploy=1
if [ "${1:-}" = "--no-deploy" ]; then deploy=0; shift; fi
what=${1:-}
[ -n "$what" ] || { sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
shift
case "$what" in
    smoke) args=(smoke) ;;
    quick) args=(--changed --parallel-only) ;;
    changed) args=(--changed) ;;
    full) args=() ;;
    *) args=("$what") ;;
esac
args+=("$@")

log() { echo "[$(date +%T)] $*"; }

if [ $deploy = 1 ]; then
    log "building $(git -C "$ROOT" rev-parse --abbrev-ref HEAD) @ $(git -C "$ROOT" rev-parse --short HEAD)"
    ./gradlew -q build || { log "build failed (see above); nothing deployed or tested"; exit 1; }
    ./gradlew -q deploy || { log "deploy failed"; exit 1; }
fi

was_running=0
systemctl is-active --quiet mcd && was_running=1
if [ $deploy = 1 ] && [ $was_running = 1 ]; then
    log "restarting pluginDev onto the new jar"
    sudo -n /usr/bin/systemctl restart mcd || { log "couldn't restart mcd (3 starts per 10 minutes at most: wait and retry)"; exit 1; }
elif [ $was_running = 0 ]; then
    log "starting pluginDev"
    sudo -n /usr/bin/systemctl start mcd || { log "couldn't start mcd (3 starts per 10 minutes at most: wait and retry)"; exit 1; }
fi

log "waiting for pluginDev to come up"
for _ in $(seq 1 60); do
    if python3 -I - <<'EOF' 2>/dev/null
import sys
sys.path.insert(0, "tools/itest")
from rcon import from_properties
from_properties("/opt/minecraft/pluginDev/server.properties").cmd("list")
EOF
    then up=1; break; fi
    sleep 5
done
[ "${up:-0}" = 1 ] || { log "pluginDev didn't answer RCON within 5 minutes"; exit 1; }

log "running: run.py ${args[*]:-(everything)}"
python3 -I tools/itest/run.py "${args[@]}"
status=$?

if [ $was_running = 0 ]; then
    log "stopping pluginDev (it was stopped before)"
    sudo -n /usr/bin/systemctl stop mcd
fi
log "done (exit $status). Results: $(readlink -f ~/claude/itest-results/latest 2>/dev/null)"
exit $status
