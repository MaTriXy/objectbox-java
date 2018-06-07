#!/usr/bin/env bash
set -e

if [ -z "$ASAN_LIB_SO" ]; then
    export ASAN_LIB_SO=/usr/lib/clang/5.0.1/lib/linux/libclang_rt.asan-x86_64.so
fi

if [ -z "$ASAN_SYMBOLIZER_PATH" ]; then
    export ASAN_SYMBOLIZER_PATH=/usr/lib/llvm-5.0/bin/llvm-symbolizer
fi

if [ -z "$ASAN_OPTIONS" ]; then
    export ASAN_OPTIONS="detect_leaks=0"
fi

echo "ASAN_LIB_SO: $ASAN_LIB_SO"
echo "ASAN_SYMBOLIZER_PATH: $ASAN_SYMBOLIZER_PATH"
echo "ASAN_OPTIONS: $ASAN_OPTIONS"
ls -l $ASAN_LIB_SO
ls -l $ASAN_SYMBOLIZER_PATH

if [[ $# -eq 0 ]] ; then
    args=test
else
    args=$@
fi
echo "Starting Gradle for target(s) \"$args\"..."

user=$(whoami)
if [[ ${user} == "jenkinsXXX-DISABLED-TO-TEST" ]]; then
    echo "WARNING!! USING GRADLE DAEMON ON JENKINS (VS. ASAN)"
    LD_PRELOAD=${ASAN_LIB_SO} ./gradlew --stacktrace ${args}
else
    echo "Starting Gradle without daemon"
    LD_PRELOAD=${ASAN_LIB_SO} ./gradlew -Dorg.gradle.daemon=false --stacktrace ${args}
fi
