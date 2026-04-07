#!/bin/sh
# Gradle wrapper script for POSIX shells

APP_HOME="$(cd "$(dirname "$0")" && pwd -P)"
APP_NAME="Gradle"
APP_BASE_NAME="${0##*/}"
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
MAX_FD=maximum

warn() { echo "$*" >&2; }
die()  { echo; echo "$*"; echo; exit 1; } >&2

case "$(uname)" in
  Darwin*)  darwin=true  ;;
  CYGWIN*)  cygwin=true  ;;
  MSYS*|MINGW*) msys=true ;;
  NONSTOP*) nonstop=true ;;
esac

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "$JAVA_HOME" ]; then
  if [ -x "$JAVA_HOME/jre/sh/java" ]; then
    JAVACMD="$JAVA_HOME/jre/sh/java"
  else
    JAVACMD="$JAVA_HOME/bin/java"
  fi
  [ ! -x "$JAVACMD" ] && die "ERROR: JAVA_HOME is invalid: $JAVA_HOME"
else
  JAVACMD=java
  command -v java >/dev/null 2>&1 || die "ERROR: JAVA_HOME not set and 'java' not found in PATH."
fi

if ! "${cygwin:-false}" && ! "${darwin:-false}" && ! "${nonstop:-false}"; then
  case $MAX_FD in
    max*) MAX_FD="$(ulimit -H -n)" ;;
  esac
  case $MAX_FD in
    ''|soft) :;;
    *) ulimit -n "$MAX_FD" ;;
  esac
fi

eval set -- $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
  "\"-Dorg.gradle.appname=$APP_BASE_NAME\"" \
  -classpath "\"$CLASSPATH\"" \
  org.gradle.wrapper.GradleWrapperMain "$@"

exec "$JAVACMD" "$@"
