#!/usr/bin/env bash
# Builds the Mingle plugin and drops it into server/plugins/.
set -euo pipefail
cd "$(dirname "$0")"
mvn -q -B -f plugin/pom.xml package
mkdir -p server/plugins
cp plugin/target/Mingle.jar server/plugins/Mingle.jar
echo "Built server/plugins/Mingle.jar"
