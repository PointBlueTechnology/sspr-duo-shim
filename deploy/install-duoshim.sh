#!/usr/bin/env bash
# Install duoshim.war into the Tomcat that runs SSPR. Safe to re-run.
#
#   sudo ./install-duoshim.sh --war duoshim.war --catalina-base /opt/netiq/idm/apps/tomcat \
#        --tomcat-user novlua [--catalina-home DIR] [--config /etc/duoshim/duoshim.properties]
#        [--example duoshim.properties.example] [--dry-run]
#
# What it does:
#   1. Checks Tomcat is 9.x (javax.servlet) and the Tomcat JVM is Java 11 or newer.
#   2. Installs the example config to --config if no config exists there (never overwrites).
#   3. Adds -Dduoshim.config=<config> to bin/setenv.sh if missing (backs the file up first).
#   4. Copies the WAR to webapps/duoshim.war (backs up any existing one).
# It does NOT restart Tomcat and does NOT write any secret. Fill in the config, then restart.
set -euo pipefail

WAR="" CB="" CH="" TUSER="" CONFIG="/etc/duoshim/duoshim.properties" DRY=0
EXAMPLE="$(cd "$(dirname "$0")/.." && pwd)/duoshim.properties.example"

die() { echo "ERROR: $*" >&2; exit 1; }
run() { if [ "$DRY" = 1 ]; then echo "DRY-RUN: $*"; else "$@"; fi; }

while [ $# -gt 0 ]; do
  case "$1" in
    --war) WAR="$2"; shift 2 ;;
    --catalina-base) CB="$2"; shift 2 ;;
    --catalina-home) CH="$2"; shift 2 ;;
    --tomcat-user) TUSER="$2"; shift 2 ;;
    --config) CONFIG="$2"; shift 2 ;;
    --example) EXAMPLE="$2"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
done

[ -n "$WAR" ] && [ -f "$WAR" ] || die "--war must name an existing duoshim.war"
[ -n "$CB" ] && [ -d "$CB/webapps" ] || die "--catalina-base must be a Tomcat base containing webapps/"
CH="${CH:-$CB}"
[ -f "$CH/lib/catalina.jar" ] || die "no lib/catalina.jar under $CH; pass --catalina-home"
[ -n "$TUSER" ] && id "$TUSER" >/dev/null 2>&1 || die "--tomcat-user must be an existing OS user"
TGROUP="$(id -gn "$TUSER")"
IS_ROOT=0; [ "$(id -u)" = 0 ] && IS_ROOT=1

# 1a. Tomcat must be 9.x (javax.*). Tomcat 10+ needs a jakarta build of the shim.
TC_VERSION="$(unzip -p "$CH/lib/catalina.jar" org/apache/catalina/util/ServerInfo.properties 2>/dev/null \
  | sed -n 's/^server.number=//p' | tr -d '\r')"
[ -n "$TC_VERSION" ] || die "cannot read the Tomcat version from $CH/lib/catalina.jar"
[ "${TC_VERSION%%.*}" = 9 ] || die "Tomcat $TC_VERSION found; this WAR needs Tomcat 9 (javax.servlet)"
echo "Tomcat $TC_VERSION: OK"

# 1b. The Tomcat JVM must be Java 11+. Use JRE_HOME/JAVA_HOME from setenv.sh, else java on PATH.
SETENV="$CB/bin/setenv.sh"
JHOME=""
if [ -f "$SETENV" ]; then
  for v in JRE_HOME JAVA_HOME; do
    JHOME="$(sed -n -E "s/^[[:space:]]*(export[[:space:]]+)?$v=[\"']?([^\"' ]+)[\"']?.*/\2/p" "$SETENV" | tail -1)"
    [ -n "$JHOME" ] && break
  done
fi
JAVA="java"; [ -n "$JHOME" ] && JAVA="$JHOME/bin/java"
JV="$("$JAVA" -version 2>&1 | sed -n -E 's/.*version "([^"]+)".*/\1/p' | head -1)"
[ -n "$JV" ] || die "cannot run $JAVA -version"
JMAJOR="$(echo "${JV%%.*}" | tr -cd '0-9')"
[ "$JMAJOR" = 1 ] && JMAJOR="$(echo "$JV" | cut -d. -f2 | tr -cd '0-9')"
[ -n "$JMAJOR" ] || die "cannot parse Java version '$JV'"
[ "$JMAJOR" -ge 11 ] || die "Tomcat JVM is Java $JV ($JAVA); the shim needs Java 11+"
echo "Tomcat JVM Java $JV ($JAVA): OK"

# 2. Config file: install the example only if nothing is there yet.
if [ -f "$CONFIG" ]; then
  echo "Config $CONFIG exists: left unchanged"
else
  [ -f "$EXAMPLE" ] || die "example config not found: $EXAMPLE (pass --example)"
  run mkdir -p "$(dirname "$CONFIG")"
  run cp "$EXAMPLE" "$CONFIG"
  run chmod 640 "$CONFIG"
  if [ "$IS_ROOT" = 1 ]; then run chown "root:$TGROUP" "$CONFIG"; run chmod 750 "$(dirname "$CONFIG")"; run chown "root:$TGROUP" "$(dirname "$CONFIG")"; fi
  echo "Config template installed at $CONFIG: fill in the values before restarting Tomcat"
fi

# 3. setenv.sh: add the system property once.
LINE="CATALINA_OPTS=\"\$CATALINA_OPTS -Dduoshim.config=$CONFIG\""
if [ -f "$SETENV" ] && grep -q 'duoshim.config' "$SETENV"; then
  echo "setenv.sh already sets duoshim.config: left unchanged"
else
  NEW_SETENV=1
  if [ -f "$SETENV" ]; then
    NEW_SETENV=0
    run cp -p "$SETENV" "$SETENV.bak-$(date +%Y%m%d%H%M%S)"
  fi
  if [ "$DRY" = 1 ]; then
    echo "DRY-RUN: append to $SETENV: $LINE"
  else
    # Appending keeps the existing owner and mode. A new setenv.sh is root-owned and
    # group-readable so the Tomcat user can't change its own startup options.
    printf '\n# sspr-duo-shim configuration file\n%s\n' "$LINE" >> "$SETENV"
    if [ "$NEW_SETENV" = 1 ]; then
      chmod 750 "$SETENV"
      [ "$IS_ROOT" = 1 ] && chown "root:$TGROUP" "$SETENV"
    fi
  fi
  echo "setenv.sh: added -Dduoshim.config=$CONFIG"
fi

# 4. WAR.
DEST="$CB/webapps/duoshim.war"
if [ -f "$DEST" ]; then
  run cp -p "$DEST" "$DEST.bak-$(date +%Y%m%d%H%M%S)"
fi
run cp "$WAR" "$DEST"
run chmod 640 "$DEST"
[ "$IS_ROOT" = 1 ] && run chown "$TUSER:$TGROUP" "$DEST"
echo "WAR installed at $DEST"

cat <<EOF

Next steps:
  1. Edit $CONFIG: Duo client ID/secret/API host, SSPR client secret, redirect URIs.
  2. Set the SSPR Forgotten Password OAuth settings (README, "SSPR Forgotten Password settings").
  3. Restart Tomcat so -Dduoshim.config takes effect, then look for "duoshim started" in catalina.out.
     Until then, /duoshim may fail to start with "no configuration file": that is expected.
EOF
