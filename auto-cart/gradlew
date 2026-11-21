#!/bin/sh
# Minimal wrapper launcher. The binary gradle/wrapper/gradle-wrapper.jar could not be
# generated offline; create it once with:  gradle wrapper --gradle-version 9.3.1
APP_HOME=$(cd "$(dirname "$0")" && pwd -P)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$JAR" ]; then
  echo "gradle-wrapper.jar is missing. Run once: gradle wrapper --gradle-version 9.3.1" >&2
  exit 1
fi
exec java -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
