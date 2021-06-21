#! /usr/bin/env tclsh

# Using package bee, see https://jeelabs.org/2012/10/04/bencode-in-lua-python-and-tcl/index.html
# and https://core.tcl-lang.org/tcllib/doc/trunk/embedded/md/tcllib/files/modules/bee/bee.md

package require Tclx ; # for try_eval
package require bee
package require uuid

# TODO - set to 0 if everything looks ok.
set verbose 0

# from https://jeelabs.org/2012/10/04/bencode-in-lua-python-and-tcl/index.html
foreach {x y} {S String N Number L ListArgs D DictArgs} {
  interp alias {} bee$x {} bee::encode$y
}

# Use the same order of functions as in Clojure, so callees at the top, callers below.

# Clojure helper function
proc dict_get {dct key} {
  if {[dict exists $dct $key]} {
    dict get $dct $key
  } else {
    return {}
  }
}

# return: Linux, ..
proc os_name {} {
  global tcl_platform
  return $tcl_platform(os)
}

proc windows? {} {
  regexp -nocase windows [os_name]
}

proc log {level msg} {
  global verbose stderr
  if {$verbose} {
    puts stderr $msg
  }
}

proc debug {msg} {
  log DEBUG $msg
}

proc warn {msg} {
  log WARN $msg
}

# read bencoded msg from socket and convert to Tcl version
# version that directly uses bee function on channel, using callbacks.
proc read_msg {s} {
  global bee_done bee_msg
  set bee_done 0
  vwait bee_done
  set bee_done 0
  set msg $bee_msg
  set bee_msg ""
  return $msg
}

proc bee_read {signal token {msg ""}} {
  global bee_done bee_msg
  if {$signal != "eof"} {
    set bee_done 1
    set bee_msg $msg
  } else {
    exit 0
  }
}

proc read_bencode {sock} {
  set result [read_msg $sock]
  debug "<- $result"
  return $result
}

# Write bencoded data to socket. Possibly write a log line.
# do not put a newline! Daemon does not understand this, not according to bencode protocol.
proc write_bencode {s data} {
  puts -nonewline $s $data
  flush $s
  debug "-> [bee::decode $data]"
}

proc msg_id {} {
  uuid::uuid generate
}

proc println_result_old {result} {
  debug "<- $result"
}

# Read result channel until status is 'done'.
# Return result map with keys :done, :need-input and keys of nrepl-result
proc read_result {sock} {
  while 1 {
    set result [read_bencode $sock]
    set status [dict_get $result status]
    if {[lsearch -exact $status "need-input"] >= 0} {
      set need_input 1
    } else {
      set need_input 0
    }
    if {[lsearch -exact $status "done"] >= 0} {
      set done 1
    } else {
      set done 0
    }
    if {$done || $need_input} {
      return [dict merge [dict create done $done need-input $need_input] $result]
    } else {
      # continue with next iteration.
    }
  }
}

proc read_print_result {sock} {
  global stdin stdout stderr
  set continue 1
  set old_value {}
  while {$continue} {
    set result [read_bencode $sock]
    foreach k {out err value ex status} {
      set $k [dict_get $result $k]
    }
    set root_ex [dict_get $result root-ex]
    if {[lsearch -exact $status "need-input"] >= 0} {
      set need_input 1
    } else {
      set need_input 0
    }
    if {[lsearch -exact $status "done"] >= 0} {
      set done 1
    } else {
      set done 0
    }
    flush stdout
    if {$out != ""} {
      puts -nonewline $out
      flush stdout
    }
    if {$err != ""} {
      puts -nonewline stderr $out
      flush stderr
    }
    if {$value != ""} {
      # debug "value: $value"
    }
    if {$ex != ""} {
      puts stderr "ex: $ex"
    }
    if {$root_ex != ""} {
      puts stderr "root-ex: $root_ex"
    }

    if {$done || $need_input} {
      return [dict merge [dict create done $done need-input $need_input] $result]
    } else {
      # continue with next iteration.
      if {$old_value == {}} {
        set old_value $value
      }
    }
  }
}

# TODO - only needed when script reads from stdin.
proc read_lines {opt rdr} {
  error "TBD: implement read_lines"
}

# bencode does not use lines, so do not use line buffering.
# TODO - try with non-blocking.
proc connect_nrepl {opt} {
  set port [dict_get $opt port]
  set s [socket localhost $port]
  fconfigure $s -blocking 1 -buffering none

  # setup callback handler just once.
  ::bee::decodeChannel $s -command bee_read -exact
  return $s
}

# Return string with expression to execute on Genie daemon.
# Make sure it's a valid string passable through nRepl/bencode
proc exec_expression {clj_ctx script main_fn script_params} {
  set clj_commands "(genied.client/exec-script \"$script\" '$main_fn $clj_ctx \[$script_params\])"
  return $clj_commands
}

# dct is a clojure dict, do some string mangling for now.
proc clj_dict_set {dct_name key value} {
  upvar $dct_name dct
  regsub {\}$} $dct " :$key \"$value\"\}" dct
}

# Eval a Genie script in a genied/nRepl session
# TODO - check logic in loop with break and continue.
proc nrepl_eval {opt clj_ctx eval_id script main_fn script_params} {
  global stdin

  set s [connect_nrepl $opt]
  write_bencode $s [beeD op [beeS clone] id [beeS [msg_id]]]
  set session [dict get [read_result $s] "new-session"]
  clj_dict_set clj_ctx session $session
  set expr [exec_expression $clj_ctx $script $main_fn $script_params]
  try_eval {
    set session_atom [dict create session $session eval_id $eval_id out $s in $s s $s]
    write_bencode $s [beeD op [beeS eval] session [beeS $session] \
                          id [beeS $eval_id] code [beeS $expr]]
    set it 0
    while {1} {
      set res [read_print_result $s]
      if {[dict_get $res "need-input"] == 1} {
        set lines [read_lines $opt stdin]
        write_bencode $s [beeD session $session id [beeS [msg_id]] \
                              op [beeS stdin] stdin [beeS $lines]]
        read_result $s ; # read ack
        incr it
        continue
      }
      break ; # if continue not reached.
    }
  } {
    warn "Caught exception: $errorResult"
  } {
    # finally
    # always send op=close, does not take extra time
    write_bencode $s [beeD op [beeS close] session [beeS $session]\
                          id [beeS [msg_id]]]
    if {[dict_get $opt closewait] == 1} {
      read_result $s
    }
    set session_atom {}
  }
}

# determine main function name based on last namespace in script
proc det_main_fn {opt script} {
  set namespace ""
  set f [open $script r]
  while {[gets $f line] >= 0} {
    if {[regexp {^\(ns ([^ \(\)]+)} $line z ns]} {
      set namespace $ns
    }
  }
  close $f
  if {$namespace != ""} {
    return "$namespace/main"
  } else {
    return "main"
  }
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

# foreach param check if it's a relative path, and convert to absolute path if so.
proc normalise_params {params} {
  set res [list]
  foreach param $params {
    lappend res [normalise_param $param]
  }
  return $res
}

# put double quote around param
proc quote_param {param} {
  return "\"$param\""
}

proc quote_params {params} {
  set res [list]
  foreach param $params {
    lappend res [quote_param $param]
  }
  join $res " "
}

# Return normalized deps.edn file, when --deps given if --deps not given, return nil
proc opt_deps {opt} {
  TODO
}

# quote dictionary for passing to Clojure as EDN
proc quote_dict {dct} {
  set res [list]
  foreach k [dict keys $dct] {
    lappend res ":$k [quote_param [dict get $dct $k]]"
  }
  return "{[join $res " "]}"
}

# TODO - add :deps (opt-deps opt)
proc create_clj_context {opt script eval_id} {
  set script2 [file normalize $script]
  set cwd [file normalize .]
  set str "{:cwd [quote_param $cwd] :script [quote_param $script2] :opt [quote_dict $opt] \
            :client \"tcl\" \
            :client-version \"0.1.0\" :protocol-version \"0.1.0\" :eval-id [quote_param $eval_id]}"
  return $str
}

# 2021-03-06: do one call to the server, which arranges the different phases/steps.
proc exec_main {opt script script_params} {
  global stdout stderr stdin
  set eval_id [msg_id]
  set clj_ctx [create_clj_context $opt $script $eval_id]
  set script2 [file normalize $script]
  set main_fn [det_main_fn $opt $script]
  nrepl_eval $opt $clj_ctx $eval_id $script2 $main_fn $script_params
  return 0
}

proc exec_script {opt argv} {
  global stdout stderr stdin
  lassign $argv script
  set script_params [quote_params [normalise_params [lrange $argv 1 end]]]
  exec_main $opt $script $script_params
}

proc print_help {opt} {
  TODO
}

# TODO (maybe) - Admin commands.

proc main {argv} {
  exec_script {port 7888} $argv
}

main $argv
