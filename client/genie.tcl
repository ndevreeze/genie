#! /usr/bin/env tclsh

# Using rep.exe, see: https://github.com/eraserhd/rep

# Notes
# GENIE_REP - set to rep executable, if it's not in the PATH, eg on Windows.

package require Tclx ; # for try_eval

set verbose 0

proc main {argv} {
  exec_script {port 7888} $argv
}

proc exec_script {opt argv} {
  global stdout stderr stdin
  lassign $argv script
  set script_params [quote_params [normalise_params [lrange $argv 1 end]]]

  log "Exec: $script"
  log "Script params: $script_params"
  set rep [det_rep]
  log "Using rep: $rep"
  exec_main $opt $rep $script $script_params
}

proc create_context {opt rep script} {
  set script2 [file normalize $script]
  set cwd [file normalize .]
  set str "{:cwd [quote_param $cwd] :script [quote_param $script2] :opt [quote_dict $opt]
            :client-version \"0.1.0\" :protocol-version \"0.1.0\"}"
  return $str
}

# quote dictionary for passing to Clojure as EDN
proc quote_dict {dct} {
  set res [list]
  foreach k [dict keys $dct] {
    lappend res ":$k [quote_param [dict get $dct $k]]"
  }
  return "{[join $res " "]}"
}

proc det_main_fn {opt script} {
  set ns "script"
  set f [open $script r]
  while {[gets $f line] >= 0} {
    if {[regexp {^\(ns ([^ \(\)]+)} $line z ns]} {
      break
    }
  }
  close $f
  return "$ns/main"
}

# 2021-03-06: do one call to the server, which arranges the different phases/steps.
proc exec_main {opt rep script script_params} {
  global stdout stderr stdin
  set ctx [create_context $opt $rep $script]
  log "Created context: $ctx"
  set cmd_verbose [det_verbose_opt $opt]
  set script2 [file normalize $script]
  set main_fn [det_main_fn $opt $script]
  log "main_fn: $main_fn"

  # 2021-03-13: do most of the processing (loading, calling) on
  # server-side; this also helps with classloaders.
  set clj_commands "(genied.client/exec-script \"$script2\" '$main_fn $ctx \[$script_params\])"
  log "clj_commands: $clj_commands"
  
  set cmd2 [list $rep {*}$cmd_verbose --no-print=value -p [dict get $opt port] $clj_commands >@stdout 2>@stderr <@stdin]
  log "cmd2: $cmd2"

  try_eval {
    set res2 "<unknown>"
    set res2 [exec -ignorestderr {*}$cmd2]
  } {
    puts stderr "Error occurred during call script: $errorResult"
    puts stderr "res2: $res2"
    puts stderr "For script: $script"
    puts stderr "Using rep: $rep"
    puts stderr "And opt: $opt"
    puts stderr "And script_params: $script_params"
    return 2
  }
  log "res2: $res2"
  return 0
}

# return list of options to give to rep.exe if global verbose is set.
proc det_verbose_opt {opt} {
  global verbose
  if {$verbose} {
    set cmd_verbose [list "--verbose" "--line='FILE:LINE:COLUMN'"]
  } else {
    set cmd_verbose [list]
  }
  return $cmd_verbose
}

proc det_rep {} {
  global tcl_platform env
  set rep [array get env GENIE_REP]
  if {$rep != ""} { 
    return [lindex $rep 1]
  }
  return rep
}

proc quote_params {params} {
  set res [list]
  foreach param $params {
    lappend res [quote_param $param]
  }
  join $res " "
}

# put double quote around param
proc quote_param {param} {
  return "\"$param\""
}

# foreach param check if it's a relative path, and convert to absolute path if so.
proc normalise_params {params} {
  set res [list]
  foreach param $params {
    lappend res [normalise_param $param]
  }
  return $res
}

# 2020-12-22: file normalise a parameter, so the server-process can find it, even though it has
# a different current-working-directory (cwd).
# - if a param starts with -, it's not a relative path.
# - if it starts with /, also not relative
# - if it start with ./, or is a single dot, then it is relative.
# - if it starts with a letter/digit/underscore, it could be relative. Check with file exists then.
proc normalise_param {param} {
  set first_char [string range $param 0 0]
  if {$first_char == "-"} {
    return $param
  } elseif {$first_char == "/"} {
    return $param
  } elseif {$param == "."} {
    return [file normalize $param]
  } elseif {[string range $param 0 1] == "./"} {
    return [file normalize $param]
  } else {
    if {[file exists $param]} {
      return [file normalize $param]
    } else {
      return $param
    }
  }
}

proc log {msg} {
  global verbose stderr
  if {$verbose} {
    puts stderr $msg
  }
}

main $argv
