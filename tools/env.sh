#!/usr/bin/env bash
# The environment of the build of LinuxDesk.
#
# The toolchain lives outside of the home directory, so that it survives a
# reset of the workspace. Source this file before a build:
#
#   source tools/env.sh
#
set -euo pipefail

TOOLCHAIN="${TOOLCHAIN:-/home/user}"

export JAVA_HOME="$TOOLCHAIN/jdk17"
export ANDROID_HOME="$TOOLCHAIN/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$TOOLCHAIN/gradle-8.7/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/home/user/gradle-home}"

# The sandbox has only two gigabytes, a big heap makes the build die.
export GRADLE_OPTS="-Xmx820m -XX:+UseSerialGC -Dfile.encoding=UTF-8 -Dorg.gradle.jvmargs=-Xmx820m -XX:+UseSerialGC"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ROOT
