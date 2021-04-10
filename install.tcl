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
  log info "Installing genie"
  try_eval {install_daemon $opt $argv} {log error "install_daemon failed: $errorResult"}
  install_client $opt $argv
  install_scripts $opt $argv
  install_config $opt $argv
  log info "Finished installing genie"
}

proc install_daemon {opt argv} {
  set target_dir [file join ~/tools genie]
  log info "Installing daemon uberjar to $target_dir"
  file mkdir $target_dir
  set target_link [file join $target_dir "genied.jar"]
  set genied_jars [glob -nocomplain -directory "genied/target/uberjar" -type f "*standalone*.jar"]
  if {[llength $genied_jars] == 0} {
    make_uberjar $opt $argv
    set genied_jars [glob -nocomplain -directory "genied/target/uberjar" -type f "*standalone*.jar"]
  }
  foreach jar $genied_jars {
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
  log info "Also installing genied.sh"
  set target_sh [file join $target_dir "genied.sh"]
  file copy -force "genied/genied.sh" $target_sh
  log info "Check if crontab has genied.sh"
  check_crontab $target_sh
}

# call leiningen to make uberjar in genied directory
proc make_uberjar {opt argv} {
  set old_wd [pwd]
  try_eval {
    cd genied
    log info "Creating uberjar with Leiningen..."
    exec -ignorestderr lein uberjar
    log info "Created uberjar with Leiningen..."
  } {
    log error "Lein uberjar failed: $errorResult"
  }
  cd $old_wd
}

proc check_crontab {target_sh} {
  try_eval {
    set res [exec -ignorestderr crontab -l | grep genied.sh]
  } {
    log warn "WARNING - Executing crontab -l | grep failed, probably genied.sh is not in crontab yet"
    log warn "ErrorResult: $errorResult"
    return
  }
  if {[regexp {.* (\S+)$} $res z crontab_path]} {
    if {[file normalize $crontab_path] == [file normalize $target_sh]} {
      log info "Ok - crontab is up-to-date, points to $target_sh"
    } else {
      log warn "WARNING - crontab does not point to latest genied.sh: $crontab_path <=> $target_sh"
    }
  } else {
    log warn "WARNING - No script reference found in crontab: $res"
  }
}

proc install_client {opt argv} {
  log info "Installing client/genie.tcl => ~/bin/genie"
  file copy -force "client/genie.tcl" "~/bin/genie"
  log info "Installing client/genie.clj => ~/bin/genie.clj"
  file copy -force "client/genie.clj" "~/bin/genie.clj"
}

# TODO - copy all templates to target-dir, could be more than 1.
proc install_scripts {opt argv} {
  set target_dir [file join ~/tools genie]
  log info "Installing genie scripts => $target_dir"
  file copy -force "scripts/genie_new.clj" "~/bin/genie_new.clj"
  file copy -force "template/template.clj" $target_dir
  file copy -force "template/deps.edn" $target_dir
}

proc install_config {opt argv} {
  set target_dir [file join ~/.config/genie]
  file mkdir $target_dir
  set target_file [file join $target_dir genie.edn]
  if {![file exists $target_file]} {
    file copy "genied/genie.edn" $target_file
    log info "Installed genie.edn in $target_dir"
  }
}

main $argv
