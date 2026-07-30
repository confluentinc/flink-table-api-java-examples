#!/usr/bin/env bash
#
# Start a JShell session with the examples on the classpath and the connection
# config + secrets loaded from .env, so you can explore the Table API live.
#
# Usage:
#   ./bin/jshell.sh
set -euo pipefail

# Always operate from the project root, regardless of where the script is invoked.
cd "$(dirname "$0")/.."

# Load .env (if present) so FLINK_PROPERTIES and the secret variables reach the JVM.
if [ -f .env ]; then
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
fi

JAR="target/flink-table-api-java-examples-1.0.jar"
if [ ! -f "$JAR" ]; then
    echo "Building $JAR ..."
    ./mvnw -q clean package -DskipTests
fi

exec jshell --class-path "$JAR" --startup ./jshell-init.jsh
