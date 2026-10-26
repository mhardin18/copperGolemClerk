#!/bin/bash
# One-time setup of the pluginDev test server, modeled on the survival server.
#
#   sudo deploy/install-pluginDev.sh
#
# Creates /opt/minecraft/pluginDev and the mcd.service unit, then starts it.
# Copies survival's server config, plugins (except BlueMap and its add-ons)
# and datapacks. Does NOT copy survival's world, claims or player data.
# Refuses to run if /opt/minecraft/pluginDev already exists.

set -euo pipefail

SRC=/opt/minecraft/survival
DST=/opt/minecraft/pluginDev
WORLD=pluginDev_world
UNIT=mcd
GAME_PORT=29001
RCON_PORT=29005
QUERY_PORT=29006
HERE=$(cd "$(dirname "$0")" && pwd)
PROJECT=$(dirname "$HERE")

die() { echo "ERROR: $*" >&2; exit 1; }
log() { echo "==> $*"; }

[ "$(id -u)" -eq 0 ] || die "run with sudo"
[ ! -e "$DST" ] || die "$DST already exists; not touching it"
[ ! -e "/etc/systemd/system/$UNIT.service" ] || die "$UNIT.service already exists"
[ ! -e "/etc/minecraft/$UNIT.env" ] || die "/etc/minecraft/$UNIT.env already exists"

# --- Port checks: nothing listening, and no other server configured to use them.
for p in $GAME_PORT $RCON_PORT $QUERY_PORT; do
    if ss -ltnuH "sport = :$p" | grep -q .; then die "port $p is already in use"; fi
    if grep -rqsE "(^|[^0-9])$p([^0-9]|$)" \
        /opt/minecraft/survival/server.properties /opt/minecraft/creative/server.properties \
        /opt/minecraft/survival/plugins/BlueMap/webserver.conf /opt/minecraft/creative/plugins/BlueMap/webserver.conf; then
        die "port $p is configured on another server"
    fi
done

PLUGIN_JAR=$(ls "$PROJECT"/build/libs/CopperGolemClerk-*.jar 2>/dev/null | head -1)
[ -n "$PLUGIN_JAR" ] || die "CopperGolemClerk jar not found; run ./gradlew build first"

# --- Server directory
log "creating $DST"
install -d -o minecraft -g minecraft -m 2775 "$DST" "$DST/config" "$DST/plugins" "$DST/$WORLD" "$DST/$WORLD/datapacks"

log "copying server config from survival"
for f in bukkit.yml spigot.yml commands.yml help.yml permissions.yml eula.txt \
         ops.json whitelist.json banned-players.json banned-ips.json; do
    cp -p "$SRC/$f" "$DST/$f"
done
cp -p "$SRC/config/paper-global.yml" "$SRC/config/paper-world-defaults.yml" "$DST/config/"

log "writing server.properties (ports $GAME_PORT/$RCON_PORT/$QUERY_PORT)"
RCON_PASS=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)
MGMT_SECRET=$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 40)
sed -e "s/^level-name=.*/level-name=$WORLD/" \
    -e "s/^level-seed=.*/level-seed=/" \
    -e "s/^server-port=.*/server-port=$GAME_PORT/" \
    -e "s/^rcon.port=.*/rcon.port=$RCON_PORT/" \
    -e "s/^query.port=.*/query.port=$QUERY_PORT/" \
    -e "s/^rcon.password=.*/rcon.password=$RCON_PASS/" \
    -e "s/^management-server-secret=.*/management-server-secret=$MGMT_SECRET/" \
    -e "s/^motd=.*/motd=pluginDev test server/" \
    "$SRC/server.properties" > "$DST/server.properties"
grep -q "^server-port=$GAME_PORT$" "$DST/server.properties" || die "server.properties rewrite failed"

# --- Plugins: every jar survival runs except BlueMap, plus CopperGolemClerk.
log "copying plugins"
for jar in "$SRC"/plugins/*.jar; do
    case "$(basename "$jar")" in
        bluemap*|BlueMap*|CopperGolemClerk*|SmartCopperGolems*) echo "    skip $(basename "$jar")" ;;
        *) cp -p "$jar" "$DST/plugins/"; echo "    $(basename "$jar")" ;;
    esac
done
cp "$PLUGIN_JAR" "$DST/plugins/"
echo "    $(basename "$PLUGIN_JAR")"

# Plugin settings only: no claims, player data or permission database.
log "copying plugin settings"
install -d "$DST/plugins/GriefPreventionData" "$DST/plugins/LuckPerms" \
           "$DST/plugins/MessageBroadcaster" "$DST/plugins/bStats" "$DST/plugins/spark"
for f in config.yml messages.yml bannedWords.txt; do
    sed "s/survival_world/$WORLD/g" "$SRC/plugins/GriefPreventionData/$f" > "$DST/plugins/GriefPreventionData/$f"
done
cp -p "$SRC/plugins/LuckPerms/config.yml" "$SRC/plugins/LuckPerms/contexts.json" "$DST/plugins/LuckPerms/"
cp -p "$SRC"/plugins/MessageBroadcaster/*.yml "$DST/plugins/MessageBroadcaster/"
cp -p "$SRC/plugins/bStats/config.yml" "$DST/plugins/bStats/"
cp -p "$SRC/plugins/spark/config.json" "$DST/plugins/spark/"

# --- Datapacks (Paper regenerates its own "bukkit" pack).
log "copying datapacks"
for dp in "$SRC/survival_world/datapacks"/*; do
    [ "$(basename "$dp")" = bukkit ] && continue
    cp -rp "$dp" "$DST/$WORLD/datapacks/"
    echo "    $(basename "$dp")"
done

# Group-writable throughout, so the minecraft group (incl. mhardin) can deploy builds.
chown -R minecraft:minecraft "$DST"
find "$DST" -type d -exec chmod 2775 {} +
find "$DST" -type f -exec chmod 664 {} +
chmod 660 "$DST/server.properties"   # holds the RCON password

# --- RCON credentials for mc-stop (same variables as the other servers).
log "writing /etc/minecraft/$UNIT.env"
[ -d /etc/minecraft ] || install -d -m 700 /etc/minecraft
umask 077
cat > "/etc/minecraft/$UNIT.env" <<EOF
MCRCON_HOST=127.0.0.1
MCRCON_PORT=$RCON_PORT
MCRCON_PASS=$RCON_PASS
EOF
chmod 600 "/etc/minecraft/$UNIT.env"
umask 022

# --- systemd
log "installing $UNIT.service"
install -m 644 "$HERE/$UNIT.service" "/etc/systemd/system/$UNIT.service"
systemctl daemon-reload
systemctl enable --now "$UNIT.service"

log "done. Watch startup with: journalctl -fu $UNIT"
