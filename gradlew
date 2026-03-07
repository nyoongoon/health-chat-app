#!/bin/sh
APP_HOME=$( cd "${0%"${0##*/}"}" > /dev/null && pwd -P ) || exit
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
JAVACMD=/Library/Java/JavaVirtualMachines/jdk-17.0.3.1.jdk/Contents/Home/bin/java
exec "$JAVACMD" "-Xmx64m" "-Xms64m" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
