# import with . ./setenv.sh

# needed for babashka script using raynes/fs. Also have uberscript where it's not needed.

# export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {clj-commons/fs {:mvn/version "1.6.307"}}}')

# also add nrepl, does this work?
# 2021-03-25: standard nrepl does not work, some invalid constructions used, like deftype.
# export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {clj-commons/fs {:mvn/version "1.6.307"} nrepl {:mvn/version "0.8.3"}}}')

# my own temporary version, commenting out some functions like clojure.lang.AReference that are not available in Babashka.
# 2021-03-25: abandoned this path for now, lots of complications.
# export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {clj-commons/fs {:mvn/version "1.6.307"} ndevreeze/nrepl {:mvn/version "0.9.0-SNAPSHOT"}}}')

# try my logger-library:
# export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {clj-commons/fs {:mvn/version "1.6.307"} ndevreeze/logger {:mvn/version "0.2.0"}}}')
# TODO - replace old fs/1.4.6 version with a new one.
export BABASHKA_CLASSPATH=src:/home/nico/.m2/repository/org/clojure/clojure/1.10.1/clojure-1.10.1.jar:/home/nico/.m2/repository/org/apache/commons/commons-compress/1.20/commons-compress-1.20.jar:/home/nico/.m2/repository/org/clojure/tools.logging/1.1.0/tools.logging-1.1.0.jar:/home/nico/.m2/repository/org/clojure/core.specs.alpha/0.2.44/core.specs.alpha-0.2.44.jar:/home/nico/.m2/repository/org/tukaani/xz/1.8/xz-1.8.jar:/home/nico/.m2/repository/org/clojure/spec.alpha/0.2.176/spec.alpha-0.2.176.jar:/home/nico/.m2/repository/ndevreeze/logger/0.2.0/logger-0.2.0.jar:/home/nico/.m2/repository/org/slf4j/slf4j-log4j12/1.7.25/slf4j-log4j12-1.7.25.jar:/home/nico/.m2/repository/clj-commons/fs/1.6.307/fs-1.6.307.jar:/home/nico/.m2/repository/clj-tuple/clj-tuple/0.2.2/clj-tuple-0.2.2.jar:/home/nico/.m2/repository/clojure/java-time/clojure.java-time/0.3.2/clojure.java-time-0.3.2.jar:/home/nico/.m2/repository/org/slf4j/slf4j-api/1.7.25/slf4j-api-1.7.25.jar:/home/nico/.m2/repository/me/raynes/fs/1.4.6/fs-1.4.6.jar:/home/nico/.m2/repository/log4j/log4j/1.2.17/log4j-1.2.17.jar


# export BABASHKA_CLASSPATH=src:/home/nico/.m2/repository/org/clojure/clojure/1.10.1/clojure-1.10.1.jar:/home/nico/.m2/repository/clj-commons/fs/1.6.307/fs-1.6.307.jar:/home/nico/.m2/repository/org/clojure/spec.alpha/0.2.176/spec.alpha-0.2.176.jar:/home/nico/.m2/repository/org/clojure/core.specs.alpha/0.2.44/core.specs.alpha-0.2.44.jar:/home/nico/.m2/repository/org/apache/commons/commons-compress/1.20/commons-compress-1.20.jar:/home/nico/.m2/repository/org/tukaani/xz/1.8/xz-1.8.jar

# uberscript and carve:
# bb -f genie.clj --uberscript genie-uberscript.clj
# carve --opts '{:paths ["genie-uberscript.clj"] :aggressive true :silent true}'


# including nrepl
export BABASHKA_CLASSPATH=src:/home/nico/.m2/repository/org/clojure/clojure/1.10.1/clojure-1.10.1.jar:/home/nico/.m2/repository/clj-commons/fs/1.6.307/fs-1.6.307.jar:/home/nico/.m2/repository/nrepl/nrepl/0.8.3/nrepl-0.8.3.jar:/home/nico/.m2/repository/org/clojure/spec.alpha/0.2.176/spec.alpha-0.2.176.jar:/home/nico/.m2/repository/org/clojure/core.specs.alpha/0.2.44/core.specs.alpha-0.2.44.jar:/home/nico/.m2/repository/org/apache/commons/commons-compress/1.20/commons-compress-1.20.jar:/home/nico/.m2/repository/org/tukaani/xz/1.8/xz-1.8.jar
