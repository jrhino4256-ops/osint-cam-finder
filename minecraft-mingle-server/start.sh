#!/usr/bin/env bash
# Builds the plugin, fetches Paper if needed, and starts the Mingle server.
#
#   EULA=true ./start.sh            # accept the Minecraft EULA and run
#   MEMORY=4G ./start.sh            # change heap size (default 2G)
#   MC_VERSION=1.21.11 ./start.sh   # pick a Paper version (needs Java 21+)
set -euo pipefail
cd "$(dirname "$0")"

MC_VERSION="${MC_VERSION:-1.21.11}"
MEMORY="${MEMORY:-2G}"
JAR="server/paper-${MC_VERSION}.jar"

if [[ -d plugin/src && ( ! -f plugin/target/Mingle.jar || -n "$(find plugin/src plugin/pom.xml -newer plugin/target/Mingle.jar -print -quit)" ) ]]; then
  ./build.sh
else
  mkdir -p server/plugins && cp plugin/target/Mingle.jar server/plugins/Mingle.jar
fi

if [[ ! -f "$JAR" ]]; then
  echo "Downloading Paper ${MC_VERSION}..."
  meta=$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/${MC_VERSION}/builds/latest")
  url=$(printf '%s' "$meta" | python3 -c 'import json,sys; print(json.load(sys.stdin)["downloads"]["server:default"]["url"])')
  sha=$(printf '%s' "$meta" | python3 -c 'import json,sys; print(json.load(sys.stdin)["downloads"]["server:default"]["checksums"]["sha256"])')
  curl -fSL -o "$JAR.part" "$url"
  echo "$sha  $JAR.part" | sha256sum -c -
  mv "$JAR.part" "$JAR"
fi

if ! grep -qs '^eula=true' server/eula.txt; then
  if [[ "${EULA:-}" == "true" ]]; then
    echo "eula=true" > server/eula.txt
  else
    echo "You must accept the Minecraft EULA (https://aka.ms/MinecraftEULA)."
    echo "Re-run with:  EULA=true ./start.sh"
    exit 1
  fi
fi

cd server
exec java -Xms"$MEMORY" -Xmx"$MEMORY" -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 \
  -jar "paper-${MC_VERSION}.jar" --nogui
