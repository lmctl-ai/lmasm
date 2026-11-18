#!/usr/bin/env bash
# Compile the fixture libraries and custom code, packaging them the way the
# dependency-analysis example expects:
#   fixtures/jars/liba.jar        third-party library A (A1, A2, A3)
#   fixtures/jars/libb.jar        third-party library B (B1, B2)
#   fixtures/appmain/classes      main-scope custom code, classes directory (Main1, Main2)
#   fixtures/apptest.jar          test-scope custom code, jar (Test1, Test2)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
  JAR="$JAVA_HOME/bin/jar"
elif command -v javac >/dev/null 2>&1; then
  JAVAC=javac
  JAR=jar
else
  TEMURIN=/home/mma/repos/lmcode-team/wp211-migration-batch1-evidence/temurin25
  JAVAC="$TEMURIN/bin/javac"
  JAR="$TEMURIN/bin/jar"
fi

rm -rf build jars appmain apptest.jar
mkdir -p build/liba build/libb build/appmain build/apptest jars appmain/classes

# javac needs the platform classpath separator (";" on Windows).
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) CP_SEP=";" ;;
  *) CP_SEP=":" ;;
esac

"$JAVAC" --release 21 -d build/liba src/com/acme/liba/*.java
"$JAVAC" --release 21 -d build/libb src/com/acme/libb/*.java
"$JAVAC" --release 21 -cp "build/liba${CP_SEP}build/libb" -d build/appmain src/com/acme/app/Main1.java src/com/acme/app/Main2.java
"$JAVAC" --release 21 -cp "build/liba${CP_SEP}build/libb" -d build/apptest src/com/acme/app/Test1.java src/com/acme/app/Test2.java

"$JAR" cf jars/liba.jar -C build/liba .
"$JAR" cf jars/libb.jar -C build/libb .
"$JAR" cf apptest.jar -C build/apptest .
cp -r build/appmain/* appmain/classes/

echo "fixtures built"
