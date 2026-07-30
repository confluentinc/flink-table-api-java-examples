#!/usr/bin/env bash
#
# Run a Table API example locally.
#
# Loads connection config + secrets from .env (git-ignored) into the environment
# so the Confluent plugin picks up FLINK_PROPERTIES and the secret variables,
# builds the shaded jar if it is missing, then runs the chosen example class.
#
# Usage:
#   ./bin/run.sh [ExampleClass] [program args...]
#
# Examples:
#   ./bin/run.sh Example_00_HelloWorld
#   ./bin/run.sh ReferenceApp_01_IntegrationAndDeployment --statement-name demo --on-conflict replace
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

EXAMPLE="${1:-ReferenceApp_01_IntegrationAndDeployment}"
[ $# -gt 0 ] && shift

JAR="target/flink-table-api-java-examples-1.0.jar"
if [ ! -f "$JAR" ]; then
    echo "Building $JAR ..."
    ./mvnw -q clean package -DskipTests
fi

# Examples live in two packages -- interactive/ (run inline and print) and app/ (deployable
# statements) -- plus TableProgramTemplate at the top level. Resolve the fully-qualified name from
# the jar so callers pass only the class name. The name is treated as a prefix, so "Example_02"
# resolves to "Example_02_UnboundedTables". Inner classes (those with a '$') are ignored.
# '|| true' keeps a no-match (unzip exits 11, grep -v exits 1) from tripping 'set -e' here, so the
# empty-result check below can report it instead of the script aborting silently.
MATCHES=$(unzip -l "$JAR" "io/confluent/flink/examples/$EXAMPLE*.class" \
        "io/confluent/flink/examples/*/$EXAMPLE*.class" 2>/dev/null \
    | awk '/\.class$/ {print $4}' \
    | grep -v '\$' \
    | sed 's#/#.#g; s#\.class$##' \
    | sort -u || true)

if [ -z "$MATCHES" ]; then
    echo "Could not find an example class matching '$EXAMPLE' in $JAR" >&2
    exit 1
fi
if [ "$(printf '%s\n' "$MATCHES" | wc -l)" -gt 1 ]; then
    echo "'$EXAMPLE' is ambiguous; matches:" >&2
    printf '%s\n' "$MATCHES" | sed 's#.*\.#  #' >&2
    echo "Please use a more specific name." >&2
    exit 1
fi
CLASS="$MATCHES"

exec java -cp "$JAR" "$CLASS" "$@"
