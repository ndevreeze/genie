#!/usr/bin/env bash

# See https://betterdev.blog/minimal-safe-bash-script-template/ for script_dir explanation.
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd -P)

# 2022-03-22: script_dir could contain cygwin format, which java does not understand. So convert to windows-path.
# use cygpath. If it does not exist, script_dir should stay as is
script_dir2=$(cygpath -w $script_dir)
if [[ -n "$script_dir2" ]] ; then
    script_dir=$script_dir2
fi

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
echo logging to $LOG_DIR/genied-$DATETIME.log
echo JAVA: $JAVA
echo script_dir: $script_dir
echo LOG_DIR: $LOG_DIR
# $JAVA -Xmx1g -jar $script_dir/genied.jar > $LOG_DIR/genied-$DATETIME.log 2>&1 &

# for running in verbose mode:
$JAVA -Xmx1g -jar $script_dir/genied.jar --verbose > $LOG_DIR/genied-$DATETIME.log 2>&1 &
