#! /usr/bin/env tclsh

# TODO - use makefile or clojure specific tool to install, not tcl.

# TODO:
# * some options to install in user-dir or system-dir (with sudo)

# TODO - should not need ndv package
package require ndv

set_log_global2 info -homedir

proc main {argv} {
  global log
  set options {
    {log-level.arg "info" "Log level to use"}
  }
  set usage ": [file tail [info script]] \[options]:"
  set opt [getoptions argv $options $usage]
  $log set_log_level [:log-level $opt]

  install $opt $argv
}

proc install {opt argv} {
  # 2021-03-11: install daemon can fail if daemon process is running, especially on Windows
  try_eval {install_daemon $opt $argv} {log error "install_daemon failed: $errorResult"}
  install_client $opt $argv
  install_scripts $opt $argv
}

proc install_daemon {opt argv} {
  set target_dir [file join ~/tools genie]
  puts "Installing daemon uberjar to $target_dir"
  file mkdir $target_dir
  set target_link [file join $target_dir "genied.jar"]
  foreach jar [glob -directory "genied/target/uberjar" -type f "*standalone*.jar"] {
    set target_jar [file join $target_dir [file tail $jar]]
    file copy -force $jar $target_jar
    file delete $target_link
    try_eval {
      file link -symbolic $target_link $target_jar
    } {
      log warn "Could not create symlink, errorResult = $errorResult"
      log warn "Possibly Windows"
      log inf "Make a copy then"
      file copy $target_jar $target_link
    }
  }
  puts "Also installing genied.sh"
  set target_sh [file join $target_dir "genied.sh"]
  file copy -force "genied/genied.sh" $target_sh
  puts "Check if crontab has genied.sh"
  check_crontab $target_sh
}

proc check_crontab {target_sh} {
  try_eval {
    set res [exec -ignorestderr crontab -l | grep genied.sh]
  } {
    puts "WARNING - Executing crontab -l | grep failed, probably genied.sh is not in crontab yet"
    puts "ErrorResult: $errorResult"
    return
  }
  if {[regexp {.* (\S+)$} $res z crontab_path]} {
    if {[file normalize $crontab_path] == [file normalize $target_sh]} {
      puts "Ok - crontab is up-to-date, points to $target_sh"
    } else {
      puts "WARNING - crontab does not point to latest genied.sh: $crontab_path <=> $target_sh"
    }
  } else {
    puts "WARNING - No script reference found in crontab: $res"
  }
}

proc install_client {opt argv} {
  puts "Installing client/genie.tcl => ~/bin/genie"
  file copy -force "client/genie.tcl" "~/bin/genie"
}

# TODO - copy all templates to target-dir, could be more than 1.
proc install_scripts {opt argv} {
  set target_dir [file join ~/tools genie]
  puts "Installing genie scripts => $target_dir"
  file copy -force "scripts/genie_new.clj" "~/bin/genie_new.clj"
  file copy -force "template/template.clj" $target_dir
  file copy -force "template/deps.edn" $target_dir
}

main $argv
