#! /usr/bin/env tclsh

# Using rep.exe, see: https://github.com/eraserhd/rep

# Notes
# GENIE_REP - set to rep executable, if it's not in the PATH, eg on Windows.

# TODO - make independent of package ndv (or use Babashka)

package require ndv

set_log_global2 info -homedir

proc main {argv} {
  global log
  set options {
    {port.arg "7888" "Clojure genie daemon port to use"}
    {main.arg "" "main function to call with cmdline args, including namespace. Default empty: determine from script ns-decl"}
    {noreload "Just call script again, do not reload, assume no changes"}
    {test "Test connection to server, by calling a simple function"}
    {log-level.arg "info" "Log level to use"}
    {nocheckserver "Do not perform server checks when an error occurs, can return a lot of lines"}
    {verbose "Add --verbose to rep and set log-level to DEBUG"}
  }
  set usage ": [file tail [info script]] \[options] <script.clj>:"
  set opt [getoptions argv $options $usage]
  if {[:verbose $opt]} {
    $log set_log_level debug
  } else {
    $log set_log_level [:log-level $opt]  
  }
  exec_script $opt $argv
}

# 2021-03-03: idea now is to do load and call main in one session. Not 2 as before.
proc exec_script {opt argv} {
  global stdout stderr stdin
  lassign $argv script
  set script_params [quote_params [normalise_params [lrange $argv 1 end]]]

  log debug "Exec: $script"
  log debug "Script params: $script_params"
  set rep [det_rep]
  log debug "Using rep: $rep"
  if {[:test $opt]} {
    test_nrepl $opt $rep
    return 
  }
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
  if {[:main $opt] != ""} {
    return [:main $opt]
  }
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
  log debug "Created context: $ctx"
  set verbose [det_verbose_opt $opt]
  set script2 [file normalize $script]
  set main_fn [det_main_fn $opt $script]
  log debug "main_fn: $main_fn"

  # 2021-03-13: do most of the processing (loading, calling) on
  # server-side; this also helps with classloaders.
  set clj_commands "(genied.client/exec-script \"$script2\" '$main_fn $ctx \[$script_params\])"
  log debug "clj_commands: $clj_commands"
  
  set cmd2 [list $rep {*}$verbose --no-print=value -p [:port $opt] $clj_commands >@stdout 2>@stderr <@stdin]
  log debug "cmd2: $cmd2"
  try_eval {
    set res2 "<unknown>"
    set res2 [exec -ignorestderr {*}$cmd2]
  } {
    log error "Error occurred during call script: $errorResult"
    log error "res2: $res2"
    log error "For script: $script"
    log error "Using rep: $rep"
    log error "And opt: $opt"
    log error "And script_params: $script_params"
    check_server $opt
    return 2
  }
  log debug "res2: $res2"
  return 0
}

# return list of options to give to rep.exe if -verbose is given.
proc det_verbose_opt {opt} {
  if {[:verbose $opt]} {
    set verbose [list "--verbose" "--line='FILE:LINE:COLUMN'"]
  } else {
    set verbose [list]
  }
  return $verbose
}

# TODO - check environment, maybe cmdline param.
# TODO - remove ref to hardcoded path
proc det_rep {} {
  global tcl_platform env
  set rep [array get env GENIE_REP]
  if {$rep != ""} { 
    return [lindex $rep 1]
  }
  if {$tcl_platform(platform) == "windows"} {
    return "C:/PCC/Nico/gitrepos/rep/rep.exe"
  }
  return rep
}

# put double quote around param, unless it's a number
# 2021-05-29: always add quotes.
proc quote_param {param} {
  if {[number? $param]} {
    # return $param
    return "\"$param\""
  } else {
    return "\"$param\""
  }
}

# return 1 iff val is a number (integer or float)
proc number? {val} {
  if {$val == ""} {
    return 0
  } else {
    string is double $val
  }
}

proc quote_params {params} {
  set res [list]
  foreach param $params {
    lappend res [quote_param $param]
  }
  join $res " "
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

# Normally only called when an error occurs. Check if server is running
proc check_server {opt} {
  global tcl_platform
  if {[:nocheckserver $opt]} {
    log warn "Not checking server, -nocheckserver given"
    return
  }
  if {$tcl_platform(platform) == "windows"} {
    try_eval {
      set res -1
      # grep returns error when text not found, so check result of netstat in tcl
      set res [exec netstat -an]
      if {[regexp -- ":[:port $opt]" $res]} {
        log warn "Port number ([:port $opt]) is found in result of netstat:"
      } else {
        log warn "Port number ([:port $opt]) is NOT found in result of netstat:"
        log warn $res
      }
    } {
      log warn "exec netstat failed with: $errorResult"
      log warn "maybe server proces is not running anymore"
    }
    log warn "Result of netstat | grep [:port $opt] : $res"
  } else {
    # think that linux is about the same.
    # maybe need another check for macbook.
    try_eval {
      set res -1
      # grep returns error when text not found, so check result of netstat in tcl
      set res [exec netstat -an]
      if {[regexp -- ":[:port $opt]" $res]} {
        log warn "Port number ([:port $opt]) is found in result of netstat:"
      } else {
        log warn "Port number ([:port $opt]) is NOT found in result of netstat:"
        log warn $res
      }
    } {
      log warn "exec netstat failed with: $errorResult"
      log warn "maybe server proces is not running anymore"
    }
    log warn "Result of netstat | grep [:port $opt] : $res"

    # also do a jcmd on linux
    try_eval {
      set res -1
      set res [exec jcmd]
      log warn "Result of jcmd:"
      log warn $res
    } {
      log warn "exec jcmd failed with: $errorResult"
      log warn "maybe server proces is not running anymore"
    }
  }
}

proc test_nrepl {opt nrepl} {
  log inf "Just calling test function"
  set cmd [list $rep -p [:port $opt] "(* 7 6)"]
  log debug "cmd: $cmd"
  set res1 [exec {*}$cmd]
  log debug "res1: $res1"  
}

main $argv
