# import with . ./setenv.sh

# needed for babashka script using raynes/fs. Also have uberscript where it's not needed.

# export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {clj-commons/fs {:mvn/version "1.6.307"}}}')

# also add nrepl, does this work?
# export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {clj-commons/fs {:mvn/version "1.6.307"} nrepl {:mvn/version "0.8.3"}}}')

# my own temporary version, commenting out some functions like clojure.lang.AReference that are not available in Babashka.
# export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {clj-commons/fs {:mvn/version "1.6.307"} ndevreeze/nrepl {:mvn/version "0.9.0-SNAPSHOT"}}}')


# export BABASHKA_CLASSPATH=src:/home/nico/.m2/repository/org/clojure/clojure/1.10.1/clojure-1.10.1.jar:/home/nico/.m2/repository/clj-commons/fs/1.6.307/fs-1.6.307.jar:/home/nico/.m2/repository/org/clojure/spec.alpha/0.2.176/spec.alpha-0.2.176.jar:/home/nico/.m2/repository/org/clojure/core.specs.alpha/0.2.44/core.specs.alpha-0.2.44.jar:/home/nico/.m2/repository/org/apache/commons/commons-compress/1.20/commons-compress-1.20.jar:/home/nico/.m2/repository/org/tukaani/xz/1.8/xz-1.8.jar

# uberscript and carve:
# bb -f genie.clj --uberscript genie-uberscript.clj
# carve --opts '{:paths ["genie-uberscript.clj"] :aggressive true :silent true}'


# including nrepl
export BABASHKA_CLASSPATH=src:/home/nico/.m2/repository/org/clojure/clojure/1.10.1/clojure-1.10.1.jar:/home/nico/.m2/repository/clj-commons/fs/1.6.307/fs-1.6.307.jar:/home/nico/.m2/repository/nrepl/nrepl/0.8.3/nrepl-0.8.3.jar:/home/nico/.m2/repository/org/clojure/spec.alpha/0.2.176/spec.alpha-0.2.176.jar:/home/nico/.m2/repository/org/clojure/core.specs.alpha/0.2.44/core.specs.alpha-0.2.44.jar:/home/nico/.m2/repository/org/apache/commons/commons-compress/1.20/commons-compress-1.20.jar:/home/nico/.m2/repository/org/tukaani/xz/1.8/xz-1.8.jar
