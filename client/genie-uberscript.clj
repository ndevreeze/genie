(ns nrepl.version
  {:author "Colin Jones"
   :added  "0.5"}
  (:import java.util.Properties))

(ns nrepl.misc
  "Misc utilities used in nREPL's implementation (potentially also
  useful for anyone extending it)."
  {:author "Chas Emerick"}
  (:refer-clojure :exclude [requiring-resolve])
  (:require [clojure.java.io :as io]))

(defn uuid
  "Returns a new UUID string."
  []
  (str (java.util.UUID/randomUUID)))

;; Copyright (c) Meikel Brandmeyer. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

;; 2021-03-23: copied all below from the split project nrepl/bencode, which has
;; some changes that make it work with Babashka.

(ns nrepl.bencode
  "A netstring and bencode implementation for Clojure."
  {:author "Meikel Brandmeyer"}
  (:require [clojure.java.io :as io])
  (:import ;; clojure.lang.RT
   [java.io ByteArrayOutputStream
    EOFException
    InputStream
    IOException
    OutputStream
    PushbackInputStream]))

;; # Motivation
;;
;; In each and every application, which contacts peer processes via some
;; communication channel, the handling of the communication channel is
;; obviously a central part of the application. Unfortunately introduces
;; handling of buffers of varying sizes often bugs in form of buffer
;; overflows and similar.
;;
;; A strong factor in this situation is of course the protocol which goes
;; over the wire. Depending on its design it might be difficult to estimate
;; the size of the input up front. This introduces more handling of message
;; buffers to accomodate for inputs of varying sizes. This is particularly
;; difficult in languages like C, where there is no bounds checking of array
;; accesses and where errors might go unnoticed for considerable amount of
;; time.
;;
;; To address these issues D. Bernstein developed the so called
;; [netstrings][net]. They are especially designed to allow easy construction
;; of the message buffers, easy and robust parsing.
;;
;; BitTorrent extended this to the [bencode][bc] protocol which also
;; includes ways to encode numbers and collections like lists or maps.
;;
;; *wire* is based on these ideas.
;;
;; [net]: http://cr.yp.to/proto/netstrings.txt
;; [bc]:  http://wiki.theory.org/BitTorrentSpecification#Bencoding
;;
;; # Netstrings
;;
;; Now let's start with the basic netstrings. They consist of a byte count,
;; followed a colon and the binary data and a trailing comma. Examples:
;;
;;     13:Hello, World!,
;;     10:Guten Tag!,
;;     0:,
;;
;; The initial byte count allows to efficiently allocate a sufficiently
;; sized message buffer. The trailing comma serves as a hint to detect
;; incorrect netstrings.
;;
;; ## Low-level reading
;;
;; We will need some low-level reading helpers to read the bytes from
;; the input stream. These are `read-byte` as well as `read-bytes`. They
;; are split out, because doing such a simple task as reading a byte is
;; mild catastrophe in Java. So it would add some clutter to the algorithm
;; `read-netstring`.
;;
;; On the other hand they might be also useful elsewhere.
;;
;; To remove some magic numbers from the code below.

(def #^{:const true} i     105)
(def #^{:const true} l     108)
(def #^{:const true} d     100)
(def #^{:const true} minus 45)

;; These two are only used boxed. So we keep them extra here.

(def e     101)
(def colon 58)

(defn #^{:private true} read-byte
  #^long [#^InputStream input]
  (let [c (.read input)]
    (when (neg? c)
      (throw (EOFException. "Invalid netstring. Unexpected end of input.")))
    ;; Here we have a quirk for example. `.read` returns -1 on end of
    ;; input. However the Java `Byte` has only a range from -128 to 127.
    ;; How does the fit together?
    ;;
    ;; The whole thing is shifted. `.read` actually returns an int
    ;; between zero and 255. Everything below the value 128 stands
    ;; for itself. But larger values are actually negative byte values.
    ;;
    ;; So we have to do some translation here. `Byte/byteValue` would
    ;; do that for us, but we want to avoid boxing here.
    (if (< 127 c) (- c 256) c)))

(defn #^{:private true :tag "[B"} read-bytes
  #^Object [#^InputStream input n]
  (let [content (byte-array n)]
    (loop [offset (int 0)
           len    (int n)]
      (let [result (.read input content offset len)]
        (when (neg? result)
          (throw
           (EOFException.
            "Invalid netstring. Less data available than expected.")))
        (when (not= result len)
          (recur (+ offset result) (- len result)))))
    content))

;; `read-long` is used for reading integers from the stream as well
;; as the byte count prefixes of byte strings. The delimiter is \:
;; for byte count prefixes and \e for integers.

(defn #^{:private true} read-long
  #^long [#^InputStream input delim]
  (loop [n (long 0)]
    ;; We read repeatedly a byte from the input…
    (let [b (read-byte input)]
      ;; …and stop at the delimiter.
      (cond
        (= b minus) (- (read-long input delim))
        (= b delim) n
        :else       (recur (+ (* n (long 10)) (- (long b) (long  48))))))))

;; ## Reading a netstring
;;
;; Let's dive straight into reading a netstring from an `InputStream`.
;;
;; For convenience we split the function into two subfunctions. The
;; public `read-netstring` is the normal entry point, which also checks
;; for the trailing comma after reading the payload data with the
;; private `read-netstring*`.
;;
;; The reason we need the less strict `read-netstring*` is that with
;; bencode we don't have a trailing comma. So a check would not be
;; beneficial here.
;;
;; However the consumer doesn't have to care. `read-netstring` as
;; well as `read-bencode` provide the public entry points, which do
;; the right thing. Although they both may reference the `read-netstring*`
;; underneath.
;;
;; With this in mind we define the inner helper function first.

(declare #^"[B" string>payload
         #^String string<payload)

(defn #^{:private true} read-netstring*
  [input]
  (read-bytes input (read-long input colon)))

;; And the public facing API: `read-netstring`.

;; Similarly the `string>payload` and `string<payload` functions
;; are defined as follows to simplify the conversion between strings
;; and byte arrays in various parts of the code.

(defn #^{:private true :tag "[B"} string>payload
  [#^String s]
  (.getBytes s "UTF-8"))

(defn #^{:private true :tag String} string<payload
  [#^"[B" b]
  (String. b "UTF-8"))

;; ## Writing a netstring
;;
;; This opposite operation – writing a netstring – is just as important.
;;
;; *Note:* We take here a byte array, just as we returned a byte
;; array in `read-netstring`. The netstring should not be concerned
;; about the actual contents. It just sees binary data.
;;
;; Similar to `read-netstring` we also split `write-netstring` into
;; the entry point itself and a helper function.

(defn #^{:private true} write-netstring*
  [#^OutputStream output #^"[B" content]
  (doto output
    (.write (string>payload (str (alength content))))
    (.write (int colon))
    (.write content)))

;; # Bencode
;;
;; However most of the time we don't want to send simple blobs of data
;; back and forth. The data sent between the communication peers usually
;; have some structure, which has to be carried along the way to the
;; other side. Here [bencode][bc] come into play.
;;
;; Bencode defines additionally to netstrings easily parseable structures
;; for lists, maps and numbers. It allows to communicate information
;; about the data structure to the peer on the other side.
;;
;; ## Tokens
;;
;; The data is encoded in tokens in bencode. There are several types of
;; tokens:
;;
;;  * A netstring without trailing comma for string data.
;;  * A tag specifiyng the type of the following tokens.
;;    The tag may be one of these:
;;     * `\i` to encode integers.
;;     * `\l` to encode lists of items.
;;     * `\d` to encode maps of item pairs.
;;  * `\e` to end the a previously started tag.
;;
;; ## Reading bencode
;;
;; Reading bencode encoded data is basically parsing a stream of tokens
;; from the input. Hence we need a read-token helper which allows to
;; retrieve the next token.

(defn #^{:private true} read-token
  [#^PushbackInputStream input]
  (let [ch (read-byte input)]
    (cond
      (= (long e) ch) nil
      (= i ch) :integer
      (= l ch) :list
      (= d ch) :map
      :else    (do
                 (.unread input (int ch))
                 (read-netstring* input)))))

;; To read the bencode encoded data we walk a long the sequence of tokens
;; and act according to the found tags.

(declare read-integer read-list read-map)

(defn read-bencode
  "Read bencode token from the input stream."
  [input]
  (let [token (read-token input)]
    (case token
      :integer (read-integer input)
      :list    (read-list input)
      :map     (read-map input)
      token)))

;; Of course integers and the collection types are have to treated specially.
;;
;; Integers for example consist of a sequence of decimal digits.

(defn #^{:private true} read-integer
  [input]
  (read-long input e))

;; *Note:* integers are an ugly special case, which cannot be
;; handled with `read-token` or `read-netstring*`.
;;
;; Lists are just a sequence of other tokens.

(declare token-seq)

(defn #^{:private true} read-list
  [input]
  (vec (token-seq input)))

;; Maps are sequences of key/value pairs. The keys are always
;; decoded into strings. The values are kept as is.

(defn #^{:private true} read-map
  [input]
  (->> (token-seq input)
       (into {} (comp (partition-all 2)
                      (map (fn [[k v]]
                             [(string<payload k) v]))))))

;; The final missing piece is `token-seq`. This a just a simple
;; sequence which reads tokens until the next `\e`.

(defn #^{:private true} token-seq
  [input]
  (->> #(read-bencode input)
       repeatedly
       (take-while identity)))

;; ## Writing bencode
;;
;; Writing bencode is similar easy as reading it. The main entry point
;; takes a string, map, sequence or integer and writes it according to
;; the rules to the given OutputStream.

(defmulti write-bencode
  "Write the given thing to the output stream. “Thing” means here a
  string, map, sequence or integer. Alternatively an ByteArray may
  be provided whose contents are written as a bytestring. Similar
  the contents of a given InputStream are written as a byte string.
  Named things (symbols or keywords) are written in the form
  'namespace/name'."
  (fn [_output thing]
    (cond
      (nil? thing) :list
      ;; borrowed from Clojure 1.9's bytes? predicate:
      (-> thing class .getComponentType (= Byte/TYPE)) :bytes
      (instance? InputStream thing) :input-stream
      (integer? thing) :integer
      (string? thing)  :string
      (symbol? thing)  :named
      (keyword? thing) :named
      (map? thing)     :map
      (or (coll? thing) (.isArray (class thing))) :list
      :else (type thing))))

(defmethod write-bencode :default
  [output x]
  (throw (IllegalArgumentException. (str "Cannot write value of type " (class x)))))

;; The following methods should be pretty straight-forward.
;;
;; The easiest case is of course when we already have a byte array.
;; We can simply pass it on to the underlying machinery.

(defmethod write-bencode :bytes
  [output bytes]
  (write-netstring* output bytes))

;; For strings we simply write the string as a netstring without
;; trailing comma after encoding the string as UTF-8 bytes.

(defmethod write-bencode :string
  [output string]
  (write-netstring* output (string>payload string)))

;; Streaming does not really work, since we need to know the
;; number of bytes to write upfront. So we read in everything
;; for InputStreams and pass on the byte array.

(defmethod write-bencode :input-stream
  [output stream]
  (let [bytes (ByteArrayOutputStream.)]
    (io/copy stream bytes)
    (write-netstring* output (.toByteArray bytes))))

;; Integers are again the ugly special case.

(defmethod write-bencode :integer
  [#^OutputStream output n]
  (doto output
    (.write (int i))
    (.write (string>payload (str n)))
    (.write (int e))))

;; Symbols and keywords are converted to a string of the
;; form 'namespace/name' or just 'name' in case its not
;; qualified. We do not add colons for keywords since the
;; other side might not have the notion of keywords.

(defmethod write-bencode :named
  [output thing]
  (let [nspace (namespace thing)
        name   (name thing)]
    (->> (str (when nspace (str nspace "/")) name)
         string>payload
         (write-netstring* output))))

;; Lists as well as maps work recursively to print their elements.

(defmethod write-bencode :list
  [#^OutputStream output lst]
  (.write output (int l))
  (doseq [elt lst]
    (write-bencode output elt))
  (.write output (int e)))

;; However, maps are a bit special because their keys are sorted
;; lexicographically based on their byte string representation.

(declare lexicographically)

(defn #^{:private true} thing>string
  [thing]
  (cond
    (string? thing)
    thing
    (or (keyword? thing)
        (symbol? thing))
    (let [nspace (namespace thing)
          name   (name thing)]
      (str (when nspace (str nspace "/")) name))))

(defmethod write-bencode :map
  [#^OutputStream output m]
  (let [translation (into {} (map (juxt (comp string>payload
                                              thing>string)
                                        identity)
                                  (keys m)))
        key-strings (sort lexicographically (keys translation))
        >value      (comp m translation)]
    (.write output (int d))
    (doseq [k key-strings]
      (write-netstring* output k)
      (write-bencode output (>value k)))
    (.write output (int e))))

;; However, since byte arrays are not `Comparable` we need a custom
;; comparator which we can feed to `sort`.

(defn #^{:private true} lexicographically
  [#^"[B" a #^"[B" b]
  (let [alen (alength a)
        blen (alength b)
        len  (min alen blen)]
    (loop [i 0]
      (if (== i len)
        (- alen blen)
        (let [x (- (int (aget a i)) (int (aget b i)))]
          (if (zero? x)
            (recur (inc i))
            x))))))

(ns nrepl.transport
  {:author "Chas Emerick"}
  (:refer-clojure :exclude [send])
  (:require
   [clojure.java.io :as io]
   [clojure.walk :as walk]
   [nrepl.bencode :as bencode]
   [clojure.edn :as edn]
   [nrepl.misc :refer [uuid]]
   nrepl.version)
  (:import
   ;;   clojure.lang.RT
   [java.io ByteArrayOutputStream EOFException PushbackInputStream PushbackReader OutputStream]
   [java.net Socket ;; SocketException ;; does not exist in Babashka.
    ]
   ;;   [java.util.concurrent BlockingQueue LinkedBlockingQueue SynchronousQueue TimeUnit]
   [java.util.concurrent LinkedBlockingQueue TimeUnit]
   ))

;; also define close here, since reify works on one protocol (interface, class)
(defprotocol Transport
  "Defines the interface for a wire protocol implementation for use
   with nREPL."
  (recv [this] [this timeout]
    "Reads and returns the next message received.  Will block.
     Should return nil the a message is not available after `timeout`
     ms or if the underlying channel has been closed.")
  (send [this msg] "Sends msg. Implementations should return the transport.")
  (close [this] "Close the transport."))

#_(defprotocol Transport
    "Defines the interface for a wire protocol implementation for use
   with nREPL."
    (recv [this] [this timeout]
      "Reads and returns the next message received.  Will block.
     Should return nil the a message is not available after `timeout`
     ms or if the underlying channel has been closed.")
    (send [this msg] "Sends msg. Implementations should return the transport."))

#_(deftype FnTransport [recv-fn send-fn close]
    Transport
    (send [this msg] (send-fn msg) this)
    (recv [this] (.recv this Long/MAX_VALUE))
    (recv [_this timeout] (recv-fn timeout))
    java.io.Closeable
    (close [_this] (close)))

;; 2021-03-23: this one could be needed, we'll see.
;; SynchronousQueue is not available, but we have java.util.concurrent.LinkedBlockingQueue and clojure.lang.PersistentQueue. Check docs: the sync-queue sounds like a blocking queue with capacity 0 or 1.
;; see if we can make this work with reify.
;; does this queue have a .poll method?
(defn fn-transport
  "Returns a Transport implementation that delegates its functionality
   to the 2 or 3 functions provided."
  ([transport-read write] (fn-transport transport-read write nil))
  ([transport-read write close]
   (let [read-queue (LinkedBlockingQueue. 1) ;; capacity of 1, is this similar to a sync-queue?
         msg-pump (future (try
                            (while true
                              (.put read-queue (transport-read)))
                            (catch Throwable t
                              (.put read-queue t))))
         failure (atom nil)] ;; does this work at this level?
     (reify Transport
       (recv [this] (.recv this Long/MAX_VALUE))
       (recv [_this timeout] (if @failure
                               (throw @failure)
                               (let [msg (.poll read-queue timeout TimeUnit/MILLISECONDS)]
                                 (if (instance? Throwable msg)
                                   (do (reset! failure msg) (throw msg))
                                   msg))))
       (send [this msg] (write msg) this)
       (close [this] (future-cancel msg-pump))))))

#_(defn fn-transport
    "Returns a Transport implementation that delegates its functionality
   to the 2 or 3 functions provided."
    ([transport-read write] (fn-transport transport-read write nil))
    ([transport-read write close]
     (let [read-queue (LinkedBlockingQueue. 1) ;; capacity of 1, is this similar to a sync-queue?
           msg-pump (future (try
                              (while true
                                (.put read-queue (transport-read)))
                              (catch Throwable t
                                (.put read-queue t))))]
       (FnTransport.
        (let [failure (atom nil)]
          #(if @failure
             (throw @failure)
             (let [msg (.poll read-queue % TimeUnit/MILLISECONDS)]
               (if (instance? Throwable msg)
                 (do (reset! failure msg) (throw msg))
                 msg))))
        write
        (fn [] (close) (future-cancel msg-pump))))))

(defmulti #^{:private true} <bytes class)

(defmethod <bytes :default
  [input]
  input)

;; 2021-03-23: comment out, hopefully not needed.
#_(defmethod <bytes (RT/classForName "[B")
    [#^"[B" input]
    (String. input "UTF-8"))

(defmethod <bytes clojure.lang.IPersistentVector
  [input]
  (vec (map <bytes input)))

(defmethod <bytes clojure.lang.IPersistentMap
  [input]
  (->> input
       (map (fn [[k v]] [k (<bytes v)]))
       (into {})))

;; 2021-03-23: replaced SocketException with a generic Exception.
(defmacro ^{:private true} rethrow-on-disconnection
  [^Socket s & body]
  `(try
     ~@body
     (catch RuntimeException e#
       (if (= "EOF while reading" (.getMessage e#))
         (throw (Exception. "The transport's socket appears to have lost its connection to the nREPL server"))
         (throw e#)))
     (catch EOFException e#
       (if (= "Invalid netstring. Unexpected end of input." (.getMessage e#))
         (throw (Exception. "The transport's socket appears to have lost its connection to the nREPL server"))
         (throw e#)))
     (catch Throwable e#
       (if (and ~s (not (.isConnected ~s)))
         (throw (Exception. "The transport's socket appears to have lost its connection to the nREPL server"))
         (throw e#)))))

(defn ^{:private true} safe-write-bencode
  "Similar to `bencode/write-bencode`, except it will only writes to the output
   stream if the whole `thing` is writable. In practice, it avoids sending partial
    messages down the transport, which is almost always bad news for the client.

   This will still throw an exception if called with something unencodable."
  [output thing]
  (let [buffer (ByteArrayOutputStream.)]
    (bencode/write-bencode buffer thing)
    (.write ^OutputStream output (.toByteArray buffer))))

(defn bencode
  "Returns a Transport implementation that serializes messages
   over the given Socket or InputStream/OutputStream using bencode."
  ([^Socket s] (bencode s s s))
  ([in out & [^Socket s]]
   (let [in (PushbackInputStream. (io/input-stream in))
         out (io/output-stream out)]
     (fn-transport
      #(let [payload (rethrow-on-disconnection s (bencode/read-bencode in))
             unencoded (<bytes (payload "-unencoded"))
             to-decode (apply dissoc payload "-unencoded" unencoded)]
         (walk/keywordize-keys (merge (dissoc payload "-unencoded")
                                      (when unencoded {"-unencoded" unencoded})
                                      (<bytes to-decode))))
      #(rethrow-on-disconnection s
                                 (locking out
                                   (doto out
                                     (safe-write-bencode %)
                                     .flush)))
      (fn []
        (if s
          (.close s)
          (do
            (.close in)
            (.close out))))))))

(defmulti uri-scheme
  "Return the uri scheme associated with a transport var."
  identity)

(defmethod uri-scheme #'bencode [_] "nrepl")

(defmethod uri-scheme :default
  [transport]
  (printf "WARNING: No uri scheme associated with transport %s\n" transport)
  "unknown")

#_(deftype QueueTransport [^BlockingQueue in ^BlockingQueue out]
    nrepl.transport.Transport
    (send [this msg] (.put out msg) this)
    (recv [_this] (.take in))
    (recv [_this timeout] (.poll in timeout TimeUnit/MILLISECONDS)))

(ns nrepl.core
  "High level nREPL client support."
  {:author "Chas Emerick"}
  (:require
   clojure.set
   [nrepl.misc :refer [uuid]]
   [nrepl.transport :as transport]
   [nrepl.version :as version]))

(defn response-seq
  "Returns a lazy seq of messages received via the given Transport.
   Called with no further arguments, will block waiting for each message.
   The seq will end only when the underlying Transport is closed (i.e.
   returns nil from `recv`) or if a message takes longer than `timeout`
   millis to arrive."
  ([transport] (response-seq transport Long/MAX_VALUE))
  ([transport timeout]
   (take-while identity (repeatedly #(transport/recv transport timeout)))))

(defn client
  "Returns a fn of zero and one argument, both of which return the current head of a single
   response-seq being read off of the given client-side transport.  The one-arg arity will
   send a given message on the transport before returning the seq.

   Most REPL interactions are best performed via `message` and `client-session` on top of
   a client fn returned from this fn."
  [transport response-timeout]
  (let [latest-head (atom nil)
        update #(swap! latest-head
                       (fn [[timestamp :as head] now]
                         (if (< timestamp now)
                           [now %]
                           head))
                       ;; nanoTime appropriate here; looking to maintain ordering, not actual timestamps
                       (System/nanoTime))
        tracking-seq (fn tracking-seq [responses]
                       (lazy-seq
                        (if (seq responses)
                          (let [rst (tracking-seq (rest responses))]
                            (update rst)
                            (cons (first responses) rst))
                          (do (update nil) nil))))
        restart #(let [head (-> transport
                                (response-seq response-timeout)
                                tracking-seq)]
                   (reset! latest-head [0 head])
                   head)]
    ^{::transport transport ::timeout response-timeout}
    (fn this
      ([] (or (second @latest-head)
              (restart)))
      ([msg]
       (transport/send transport msg)
       (this)))))

(defn- take-until
  "Like (take-while (complement f) coll), but includes the first item in coll that
   returns true for f."
  [f coll]
  (let [[head tail] (split-with (complement f) coll)]
    (concat head (take 1 tail))))

(defn- delimited-transport-seq
  "Returns a function of one arument that performs described below.
   The following \"message\" is the argument of the function returned by this function.

    - Merge delimited-slots to the message
    - Sends a message via client
    - Filter only items related to the delimited-slots of client's response seq
    - Returns head of the seq that will terminate
      upon receipt of a :status, when :status is an element of termination-statuses"
  [client termination-statuses delimited-slots]
  (with-meta
    (comp (partial take-until (comp #(seq (clojure.set/intersection % termination-statuses))
                                    set
                                    :status))
          (let [keys (keys delimited-slots)]
            (partial filter #(= delimited-slots (select-keys % keys))))
          client
          #(merge % delimited-slots))
    (-> (meta client)
        (update-in [::termination-statuses] (fnil into #{}) termination-statuses)
        (update-in [::taking-until] merge delimited-slots))))

(defn message
  "Sends a message via [client] with a fixed message :id added to it
   by `delimited-transport-seq`.
   Returns the head of the client's response seq, filtered to include only
   messages related to the message :id that will terminate upon receipt of a
   \"done\" :status."
  [client {:keys [id] :as msg :or {id (uuid)}}]
  (let [f (delimited-transport-seq client #{"done" :done} {:id id})]
    (f msg)))

;; IllegalStateException is not known, so use a generic Exception for now
(defn new-session
  "Provokes the creation and retention of a new session, optionally as a clone
   of an existing retained session, the id of which must be provided as a :clone
   kwarg.  Returns the new session's id."
  [client & {:keys [clone]}]
  (let [resp (first (message client (merge {:op "clone"} (when clone {:session clone}))))]
    (or (:new-session resp)
        (throw (Exception.
                (str "Could not open new session; :clone response: " resp))))))

#_(defn new-session
    "Provokes the creation and retention of a new session, optionally as a clone
   of an existing retained session, the id of which must be provided as a :clone
   kwarg.  Returns the new session's id."
    [client & {:keys [clone]}]
    (let [resp (first (message client (merge {:op "clone"} (when clone {:session clone}))))]
      (or (:new-session resp)
          (throw (IllegalStateException.
                  (str "Could not open new session; :clone response: " resp))))))

(defn client-session
  "Returns a function of one argument.  Accepts a message that is sent via the
   client provided with a fixed :session id added to it.  Returns the
   head of the client's response seq, filtered to include only
   messages related to the :session id that will terminate when the session is
   closed."
  [client & {:keys [session clone]}]
  (let [session (or session (apply new-session client (when clone [:clone clone])))]
    (delimited-transport-seq client #{"session-closed"} {:session session})))

(defn connect
  "Connects to a socket-based REPL at the given host (defaults to 127.0.0.1) and port,
   returning the Transport (by default `nrepl.transport/bencode`)
   for that connection.

   Transports are most easily used with `client`, `client-session`, and
   `message`, depending on the semantics desired."
  [& {:keys [port host transport-fn] :or {transport-fn transport/bencode
                                          host "127.0.0.1"}}]
  {:pre [transport-fn port]}
  (transport-fn (java.net.Socket. ^String host (int port))))

(defn- ^java.net.URI to-uri
  [x]
  {:post [(instance? java.net.URI %)]}
  (if (string? x)
    (java.net.URI. x)
    x))

(defn- socket-info
  [x]
  (let [uri (to-uri x)
        port (.getPort uri)]
    (merge {:host (.getHost uri)}
           (when (pos? port)
             {:port port}))))

(def ^{:private false} uri-scheme #(-> (to-uri %) .getScheme .toLowerCase))

(defmulti url-connect
  "Connects to an nREPL endpoint identified by the given URL/URI.  Valid
   examples include:

      nrepl://192.168.0.12:7889
      telnet://localhost:5000
      http://your-app-name.heroku.com/repl

   This is a multimethod that dispatches on the scheme of the URI provided
   (which can be a string or java.net.URI).  By default, implementations for
   nrepl (corresponding to using the default bencode transport) and
   telnet (using the `nrepl.transport/tty` transport) are
   registered.  Alternative implementations may add support for other schemes,
   such as HTTP, HTTPS, JMX, existing message queues, etc."
  uri-scheme)

;; TODO: oh so ugly
(defn- add-socket-connect-method!
  [protocol connect-defaults]
  (defmethod url-connect protocol
    [uri]
    (apply connect (mapcat identity
                           (merge connect-defaults
                                  (socket-info uri))))))

#_(add-socket-connect-method! "nrepl+edn" {:transport-fn transport/edn
                                           :port 7888})
(add-socket-connect-method! "nrepl" {:transport-fn transport/bencode
                                     :port 7888})
#_(add-socket-connect-method! "telnet" {:transport-fn transport/tty})

(defmethod url-connect :default
  [uri]
  (throw (IllegalArgumentException.
          (format "No nREPL support known for scheme %s, url %s" (uri-scheme uri) uri))))

(ns me.raynes.fs
  "File system utilities in Clojure"
  (:refer-clojure :exclude [name parents])
  (:require [clojure.zip :as zip]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh])
  (:import [java.io File FilenameFilter]
           [java.nio.file Files Path LinkOption CopyOption]
           [java.nio.file.attribute FileAttribute]))

;; Once you've started a JVM, that JVM's working directory is set in stone
;; and cannot be changed. This library will provide a way to simulate a
;; working directory change. `cwd` is considered to be the current working
;; directory for functions in this library. Unfortunately, this will only
;; apply to functions inside this library since we can't change the JVM's
;; actual working directory.
(def ^{:doc "Current working directory. This cannot be changed in the JVM.
             Changing this will only change the working directory for functions
             in this library."
       :dynamic true}
  *cwd* (.getCanonicalFile (io/file ".")))

(let [homedir (io/file (System/getProperty "user.home"))
      usersdir (.getParent homedir)])

;; Library functions will call this function on paths/files so that
;; we get the cwd effect on them.
(defn ^File file
  "If `path` is a period, replaces it with cwd and creates a new File object
   out of it and `paths`. Or, if the resulting File object does not constitute
   an absolute path, makes it absolutely by creating a new File object out of
   the `paths` and cwd."
  [path & paths]
  (when-let [path (apply
                   io/file (if (= path ".")
                             *cwd*
                             path)
                   paths)]
    (if (.isAbsolute ^File path)
      path
      (io/file *cwd* path))))

(defn normalized
  "Return normalized (canonical) file."
  [path]
  (.getCanonicalFile (file path)))

(extend-protocol io/Coercions
  Path
  (as-file [this] (.toFile this))
  (as-url [this] (.. this (toFile) (toURL))))

;; Rewrite directory? and delete-dir to include LinkOptions.
; Taken from https://github.com/jkk/clj-glob. (thanks Justin!)
;; for using raynes.fs (clj-commons/fs):
;; export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {clj-commons/fs {:mvn/version "1.6.307"}}}')

;; TODO:

;; * logging: own lib or something babashka specific? or a simple log
;;   function with 2 levels and one *verbose* option.

(ns genie
  (:require [bencode.core :as b]
            [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [me.raynes.fs :as fs]
            [nrepl.core :as nrepl]
            ))

(def cli-options
  [["-p" "--port PORT" "Genie daemon port number"
    :default 7888
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-v" "--verbose" "Verbose output"]
   ["-h" "--help"]])

(defn read-result
  "Read result channel until 'done' found"
  [in]
  (let [result (b/read-bencode in)
        val-bytes (get result "value")
        out-bytes (get result "out")
        status-bytes (get result "status")
        done (and status-bytes
                  (= "done" (String. (first status-bytes))))]
    
    #_(println "Complete result: " result)
    #_(when done
        (println "done var is set to TRUE: " done))
    (when out-bytes
      (print (String. out-bytes))
      #_(println "out-bytes:" (String. out-bytes))
      #_(println "  class:" (class out-bytes)))
    #_(when val-bytes
        (println "val-bytes:" (String. val-bytes)))
    #_(when status-bytes
        (println "status-bytes:" status-bytes)
        (println "  class: " (class status-bytes))
        (doseq [item status-bytes]
          (println "  item: " item)
          (println "    class: " (class item))
          (println "    string: " (String. item))
          (when (= "done" (String. item))
            (println "It seems we are done here")))

        #_(println "  as string: " (String. status-bytes)))

    #_(println "=======================")


    (cond val-bytes (String. val-bytes)
          result (str "Complete result: " result)
          :else "No result in value field and complete result")
    (when (not done)
      (recur in))
    ))

;; from https://book.babashka.org/#_interacting_with_an_nrepl_server
(defn nrepl-eval [host port expr]
  (let [s (java.net.Socket. host port)
        out (.getOutputStream s)
        in (java.io.PushbackInputStream. (.getInputStream s))
        _ (b/write-bencode out {"op" "clone"})
        _ (read-result in)
        _ (b/write-bencode out {"op" "eval" "code" expr})]
    (read-result in)))

#_(defn nrepl-eval [host port expr]
    (let [s (java.net.Socket. host port)
          out (.getOutputStream s)
          in (java.io.PushbackInputStream. (.getInputStream s))
          _ (b/write-bencode out {"op" "eval" "code" expr})
          result (b/read-bencode in)
          bytes (get result "value")
          out-bytes (get result "out")]
      (when out-bytes
        (println "out-bytes:" (String. out-bytes)))
      (cond bytes (String. bytes)
            result (str "Complete result: " result)
            :else "No result in value field and complete result")))

;; from https://book.babashka.org/#_interacting_with_an_nrepl_server
;; 2021-03-23: from
;; https://github.com/nrepl/nrepl/blob/master/src/clojure/nrepl/cmdline.clj
;; run-repl and adapted
(def running-repl (atom {:transport nil
                         :client nil}))

(defn nrepl-eval2
  ([host port expr]
   (nrepl-eval2 host port expr nil))
  ([host port expr {:keys [prompt err out value transport]
                    :or {prompt #(print (str % "=> "))
                         err print
                         out print
                         value println
                         transport #'nrepl.transport/bencode}}]
   (let [transport (nrepl/connect :host host :port port :transport-fn transport)
         client (nrepl/client transport Long/MAX_VALUE)]
     ;; (println (repl-intro)) ;; repl-intro not found, not important.
     ;; We take 50ms to listen to any greeting messages, and display the value
     ;; in the `:out` slot.
     (future (->> (client)
                  (take-while #(nil? (:id %)))
                  (run! #(when-let [msg (:out %)] (print msg)))))
     (Thread/sleep 50)
     (let [session (nrepl/client-session client)
           ns (atom "user")]
       (swap! running-repl assoc :transport transport)
       (swap! running-repl assoc :client session)
       (do (doseq [res (nrepl/message session {:op "eval" :code expr})]
             (when (:value res) (value (:value res)))
             (when (:out res) (out (:out res)))
             (when (:err res) (err (:err res)))
             (when (:ns res) (reset! ns (:ns res)))))))))

(defn create-context
  [opt script]
  {})

(defn det-main-fn
  [opt script]
  "test-dyn-cl/main")

;;   set clj_commands "(genied.client/exec-script \"$script2\" '$main_fn $ctx \[$script_params\])"
(defn exec-expression
  [ctx script main-fn script-params]
  (str "(genied.client/exec-script \"" script "\" '" main-fn " " ctx " [" script-params "])"))

;; TODO
(defn normalize-params
  [params]
  params)

;; TODO - check if number, maybe not needed.
(defn quote-param
  [param]
  (str "\"" param "\""))

(defn quote-params
  [params]
  (str/join " " (map quote-param params)))

;; some poor-man logging for now
(defn debug
  "Log if verbose is set"
  [& msg]
  (println (str/join " " msg)))

;; TODO - check how babashka returns result. It could/should also redirect stdin/out/err.
(defn exec-script
  "Execute given script with opt and script-params"
  [{:keys [port verbose] :as opt} script script-params]
  (let [ctx (create-context opt script)
        script2 (fs/normalized script)
        main-fn (det-main-fn opt script2)
        script-params2 (-> script-params normalize-params quote-params)
        expr (exec-expression ctx script2 main-fn script-params2)
        expr2 "(println 12)"
        expr3 "(* 7 6)"]
    ;; (debug "Exec-expr: " expr)
    ;; (debug "Exec-expr2: " expr2)
    ;; (debug "Exec-expr3: " expr3)
    ;; (debug "Port: " port)
    (nrepl-eval "localhost" port expr))

  )

(defn main
  "Main function"
  [opt args]
  (exec-script opt (first args) (rest args)))

#_(defn main
    "Main function"
    [opt args]
    (let [port (:port opt)]
      (println "args: " args)
      (nrepl-eval port "(+ 1 2 3)")));; => "6")


(let [opts (cli/parse-opts *command-line-args* cli-options)]
  (main (:options opts) (:arguments opts)))




