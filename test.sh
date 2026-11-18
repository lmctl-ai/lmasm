#!/usr/bin/env bash
# Full gate: build the lmasm host (unless LMASM_SKIP_BUILD=1), compile fixtures,
# run the lmcode dependency-analysis example through the host on PATH, and diff
# the output against the pinned expectation.
#
# CI calls this with LMASM_SKIP_BUILD=1 after `mvn verify`; the native smoke job
# additionally puts a directory containing an `lmasm` binary first on PATH.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

if [ "${LMASM_SKIP_BUILD:-0}" != "1" ]; then
  TEMURIN=/home/mma/repos/lmcode-team/wp211-migration-batch1-evidence/temurin25
  if ! command -v mvn >/dev/null 2>&1; then
    MVN="$HOME/.m2/wrapper/dists/apache-maven-3.9.9-bin/33b4b2b4/apache-maven-3.9.9/bin/mvn"
    if [ -z "${JAVA_HOME:-}" ] && [ -x "$TEMURIN/bin/java" ]; then
      export JAVA_HOME="$TEMURIN"
    fi
  else
    MVN=mvn
    if [ -z "${JAVA_HOME:-}" ] && [ -x "$TEMURIN/bin/java" ]; then
      export JAVA_HOME="$TEMURIN"
    fi
  fi
  "$MVN" -q -DskipTests package
fi

./fixtures/build-fixtures.sh

export PATH="$PWD/bin:$PATH"
LMSCRIPT="${LMSCRIPT:-lmscript}"
"$LMSCRIPT" examples/dep-analysis.lms > /tmp/lmasm-dep-analysis.out

cat > /tmp/lmasm-dep-analysis.expect <<'EOF'
fixtures/jars/liba.jar, 3, 2
fixtures/jars/libb.jar, 2, 1
{"ok":true,"result":null}
EOF

if diff -u /tmp/lmasm-dep-analysis.expect /tmp/lmasm-dep-analysis.out; then
  echo "PASS lmasm dependency analysis"
else
  echo "FAIL output differs" >&2
  exit 1
fi
