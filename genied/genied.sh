#!/usr/bin/env bash

# See https://betterdev.blog/minimal-safe-bash-script-template/ for script_dir explanation.
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd -P)
DATETIME=`date +%Y-%m-%d-%H-%M-%S`

/usr/bin/java -jar $script_dir/genied.jar > ~/log/genied-$DATETIME.log 2>&1 &
