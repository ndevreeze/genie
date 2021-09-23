#!/usr/bin/env bash

# See https://betterdev.blog/minimal-safe-bash-script-template/ for script_dir explanation.
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd -P)
DATETIME=`date +%Y-%m-%d-%H-%M-%S`

if [[ -n "$GENIE_LOG_DIR" ]] ; then
    LOG_DIR=$GENIE_LOG_DIR
else
    LOG_DIR=~/log
fi

# check GENIE_JAVA_CMD, JAVA_CMD, JAVA_HOME and default java.
if [[ -n "$GENIE_JAVA_CMD" ]]; then
    JAVA=$GENIE_JAVA_CMD
else
    if [[ -n "$JAVA_CMD" ]]; then
        JAVA=$JAVA_CMD
    else
        if [[ -n "$JAVA_HOME" ]] && [[ -x "$JAVA_HOME/bin/java" ]]; then
            JAVA="$JAVA_HOME/bin/java"
        else
            JAVA=java
        fi
    fi
fi

# By default max 1GB of memory
$JAVA -Xmx1g -jar $script_dir/genied.jar > $LOG_DIR/genied-$DATETIME.log 2>&1 &
