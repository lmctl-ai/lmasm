#!/usr/bin/env bash
# Build the GraalVM native lmasm executable into target/lmasm-native (Windows: .exe).
# Requires: mvn package already run (target/lmasm.jar) and native-image on PATH.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
timeout_cmd() { if [ -x /usr/bin/timeout ]; then /usr/bin/timeout "$@"; elif command -v gtimeout >/dev/null 2>&1; then gtimeout "$@"; else timeout "$@"; fi; }
test -f target/lmasm.jar || { echo "target/lmasm.jar missing; run 'mvn package' first" >&2; exit 2; }
out=target/lmasm-native
flags=(-H:+UnlockExperimentalVMOptions -J-Xmx4g -H:NumberOfThreads=2 --no-fallback -march=compatibility -H:+ReportExceptionStackTraces -H:IncludeResources='lmasm[.-].*' -cp target/lmasm.jar)
if [ "$(uname -s)" = Linux ]; then
  flags+=("-H:+StaticExecutableWithDynamicLibC" "-H:NativeLinkerOption=-static-libstdc++" "-H:NativeLinkerOption=-static-libgcc")
fi
# GraalVM on Windows ships native-image as a .cmd, which git-bash cannot find
# via PATH and /usr/bin/timeout cannot exec; call it directly.
if command -v native-image >/dev/null 2>&1; then
  timeout_cmd 1800 native-image "${flags[@]}" io.lmctl.lmasm.Main "$out"
elif [ -n "${JAVA_HOME:-}" ] && [ -f "$JAVA_HOME/bin/native-image.cmd" ]; then
  "$JAVA_HOME/bin/native-image.cmd" "${flags[@]}" io.lmctl.lmasm.Main "$out"
else
  echo "native-image is required" >&2
  exit 2
fi
test -f "$out" || test -f "$out.exe"
