#!/bin/sh
# ----------------------------------------------------------------------------
# Maven Wrapper script for Unix/Linux/macOS
# ----------------------------------------------------------------------------

set -e

# Resolve target directory
PRG="$0"
while [ -h "$PRG" ]; do
  ls=`ls -ld "$PRG"`
  link=`expr "$ls" : '.*-> \(.*\)$'`
  if expr "$link" : '/.*' > /dev/null; then
    PRG="$link"
  else
    PRG=`dirname "$PRG"`/"$link"
  fi
done
BASE_DIR=`dirname "$PRG"`
BASE_DIR=`cd "$BASE_DIR" >/dev/null 2>&1 && pwd`

# Check if mvn is installed system-wide, else fallback or use installed Maven
if command -v mvn >/dev/null 2>&1; then
  exec mvn "$@"
fi

# Fallback: find Maven home or notify user
if [ -n "$M2_HOME" ] && [ -x "$M2_HOME/bin/mvn" ]; then
  exec "$M2_HOME/bin/mvn" "$@"
fi

echo "Error: Neither 'mvn' nor Maven Wrapper installation could be found. Please install Maven or Java." >&2
exit 1
