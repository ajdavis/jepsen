(ns jepsen.store
  "Persistent storage for test runs and later analysis."
  (:refer-clojure :exclude [load])
  (:require [clojure.data.fressian :as fress]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure [string :as str]
                     [walk :as walk]]
            [clojure.tools.logging :refer :all]
            [clj-time.core :as time]
            [clj-time.local :as time.local]
            [clj-time.coerce :as time.coerce]
            [clj-time.format :as time.format]
            [fipp.edn :refer [pprint]]
            [unilog.config :as unilog]
            [multiset.core :as multiset]
            [jepsen [fs-cache :refer [write-atomic!]]
                    [util :as util]]
            [jepsen.store [fressian :as jsf]
                          [format :as store.format]])
  (:import (java.util AbstractList)
           (java.io Closeable
                    File)
           (java.nio.file Files
                          FileSystems
                          Path)
           (java.nio.file.attribute FileAttribute
                                    PosixFilePermissions)
           (java.time Instant)
           (org.fressian.handlers WriteHandler ReadHandler)
           (multiset.core MultiSet)))

(def base-dir "store")

; These were moved into their own namespace, and are here for backwards
; compatibility
(def write-handlers jsf/write-handlers)
(def read-handlers  jsf/read-handlers)

(defn ^File path
  "With one arg, a test, returns the directory for that test's results. Given
  additional arguments, returns a file with that name in the test directory.
  Nested paths are flattened: (path t [:a [:b :c] :d) expands to .../a/b/c/d.
  Nil path components are ignored: (path t :a nil :b) expands to .../a/b.

  Test must have only two keys: :name, and :start-time. :start-time may be a
  string, or a DateTime."
  ([test]
   (assert (:name test))
   (assert (:start-time test))
   (io/file base-dir
            (:name test)
            (let [t (:start-time test)]
              (if (string? t)
                t
                (time.local/format-local-time t :basic-date-time)))))
  ([test & args]
   (->> args
        flatten
        (remove nil?)
        (map str)
        (apply io/file (path test)))))

(defn ^File path!
  "Like path, but ensures the path's containing directories exist."
  [& args]
  (let [path (apply path args)]
    (io/make-parents path)
    path))

(defn ^File jepsen-file
  "Gives the path to a .jepsen file encoding all the results from a test."
  [test]
  (path test "test.jepsen"))

(defn ^File jepsen-file!
  "Gives the path to a .jepsen file, ensuring its directory exists."
  [test]
  (path! test "test.jepsen"))

(defn ^File fressian-file
  "Gives the path to a fressian file encoding all the results from a test."
  [test]
  (path test "test.fressian"))

(defn ^File fressian-file!
  "Gives the path to a fressian file encoding all the results from a test,
  ensuring its containing directory exists."
  [test]
  (path! test "test.fressian"))

(def default-nonserializable-keys
  "What keys in a test can't be serialized to disk, by default?"
  #{:barrier :db :os :net :client :checker :nemesis :generator :model :remote})

(defn nonserializable-keys
  "What keys in a test can't be serialized to disk? The union of default
  nonserializable keys, plus any in :nonserializable-keys."
  [test]
  (into default-nonserializable-keys (:nonserializable-keys test)))

(defn serializable-test
  "Takes a test and returns it without its serializable keys."
  [test]
  (apply dissoc test (nonserializable-keys test)))
(defn load-fressian-file
  "Loads an arbitrary Fressian file."
  [file]
  (with-open [is (io/input-stream file)
              in ^Closeable (jsf/reader is)]
    (-> (fress/read-object in)
        jsf/postprocess-fressian)))

(defn load-jepsen-file
  "Loads a test from an arbitrary Jepsen file. This is lazy, and retains a
  filehandle which will remain open until all references to this test are gone
  and the GC kicks in."
  [file]
  (store.format/read-test (store.format/open file)))

(defn load
  "Loads a specific test by name and time. Prefers .jepsen file, falls back to
  .fressian."
  [test-name test-time]
  (let [test     {:name       test-name
                  :start-time test-time}
        jepsen   (jepsen-file test)
        fressian (fressian-file test)]
    (if (.exists jepsen)
      (load-jepsen-file jepsen)
      (load-fressian-file fressian))))

(defn class-name->ns-str
  "Turns a class string into a namespace string (by translating _ to -)"
  [class-name]
  (str/replace class-name #"_" "-"))

(defn edn-tag->constructor
  "Takes an edn tag and returns a constructor fn taking that tag's value and
  building an object from it."
  [tag]
  (let [c (resolve tag)]
    (when (nil? c)
      (throw (RuntimeException. (str "EDN tag " (pr-str tag) " isn't resolvable to a class"))))

    (when-not ((supers c) clojure.lang.IRecord)
      (throw (RuntimeException.
             (str "EDN tag " (pr-str tag)
                  " looks like a class, but it's not a record,"
                  " so we don't know how to deserialize it."))))

    (let [; Translate from class name "foo.Bar" to namespaced constructor fn
          ; "foo/map->Bar"
          constructor-name (-> (name tag)
                               class-name->ns-str
                               (str/replace #"\.([^\.]+$)" "/map->$1"))
          constructor (resolve (symbol constructor-name))]
      (when (nil? constructor)
        (throw (RuntimeException.
               (str "EDN tag " (pr-str tag) " looks like a record, but we don't"
                    " have a map constructor " constructor-name " for it"))))
      constructor)))

(def memoized-edn-tag->constructor (memoize edn-tag->constructor))

(defn default-edn-reader
  "We use defrecords heavily and it's nice to be able to deserialize them."
  [tag value]
  (if-let [c (memoized-edn-tag->constructor tag)]
    (c value)
    (throw (RuntimeException.
             (str "Don't know how to read edn tag " (pr-str tag))))))

(defn load-results-edn
  "Loads the results map for a test by parsing the result.edn file, instead of
  test.jepsen."
  [test]
  (with-open [file (java.io.PushbackReader.
                     (io/reader (path test "results.edn")))]
    (edn/read {:default default-edn-reader} file)))

(defn load-results
  "Loads the results map for a test by name and time. Prefers a lazy map from
  test.fressian; falls back to parsing results.edn."
  [test-name test-time]
  (let [test   {:name test-name, :start-time test-time}
        jepsen (jepsen-file test)]
    (if (.exists jepsen)
      (:results (load-jepsen-file jepsen))
      (load-results-edn test))))

(def memoized-load-results (memoize load-results))

(defn dir?
  "Is this a directory?"
  [^File f]
  (.isDirectory f))

(defn file-name
  "Maps a File to a string name."
  [^File f]
  (.getName f))

(defn virtual-dir?
  "Is this a . or .. directory entry?"
  [f]
  (let [n (file-name f)]
    (or (= n ".")
        (= n ".."))))

(defn symlink?
  "Is this a symlink?"
  [^File f]
  (Files/isSymbolicLink (.toPath f)))

(defn test-names
  "Returns a seq of all known test names."
  []
  (->> (io/file base-dir)
       (.listFiles)
       (remove virtual-dir?)
       (remove symlink?)
       (filter dir?)
       (map file-name)))

(defn tests
  "If given a test name, returns a map of test runs to deref-able tests. With
  no test name, returns a map of test names to maps of runs to deref-able
  tests."
  ([]
   (->> (test-names)
        (map (juxt identity tests))
        (into {})))
  ([test-name]
   (assert test-name)
   (->> test-name
        name
        (io/file base-dir)
        (.listFiles)
        (remove virtual-dir?)
        (remove symlink?)
        (filter dir?)
        (map file-name)
        (map (fn [f] [f (delay (load test-name f))]))
        (into {}))))

(defn write-jepsen!
  "Takes a test and saves it as a .jepsen binary file."
  [test]
  (write-atomic! [tmp (path! test "test.jepsen")]
                 (with-open [w (store.format/open tmp)]
                   (store.format/write-test! w (serializable-test test))))
  test)

(defn migrate-to-jepsen-format!
  "Loads every test and copies their Fressian files to the new on-disk
  format."
  []
  (->> (tests)
       vals
       (mapcat vals)
       (pmap (fn [test]
              (let [t1 (System/nanoTime)]
                (try
                  (when-not (.exists (path @test "test.jepsen"))
                    (let [t2 (System/nanoTime)
                          _  (write-jepsen! @test)
                          t3 (System/nanoTime)]
                      (info "Migrated" (str (path @test))
                            "in"  (float (* 1e-9 (- t2 t1)))
                            "+" (float (* 1e-9 (- t3 t2))) "seconds")))
                  (catch java.io.FileNotFoundException _
                    ; No file; don't worry about it.
                    )
                  (catch java.io.EOFException _
                    ; File truncated, probably crashed during write
                    (warn "Couldn't migrate test; .fressian truncated"))
                  (catch Exception e
                    (warn e "Couldn't migrate test"))))))
       dorun))

(defn latest
  "Loads the latest test"
  []
  (when-let [t (->> (tests)
                    vals
                    (apply concat)
                    sort
                    util/fast-last
                    val)]
    @t))

(defn update-symlink!
  "Takes a test and a symlink path. Creates a symlink from that path to the
  test directory, if it exists."
  [test dest]
  (when (.exists (path test))
    (let [src  (.toPath (path test))
          dest (.. FileSystems
                   getDefault
                   (getPath base-dir (into-array dest)))
          _    (Files/deleteIfExists dest)]
      (Files/createSymbolicLink dest (.relativize (.getParent dest) src)
                                (make-array FileAttribute 0)))))

(defn update-current-symlink!
  "Creates a `current` symlink to the currently running test, if a store
  directory exists."
  [test]
  (update-symlink! test ["current"]))

(defn update-symlinks!
  "Creates `latest` and `current` symlinks to the given test, if a store
  directory exists."
  [test]
  (doseq [dest [["current"]
                ["latest"]
                [(:name test) "latest"]]]
    (update-symlink! test dest)))

(defmacro with-out-file
  "Binds stdout to a file for the duration of body."
  [test filename & body]
  `(let [filename# (path! ~test ~filename)]
     (with-open [w# (io/writer filename#)]
       (try
         (binding [*out* w#] ~@body)
         (finally
           (info "Wrote" (.getCanonicalPath filename#)))))))

(defn write-results!
  "Writes out a results.edn file."
  [test]
  (with-out-file test "results.edn"
    (pprint (:results test))))

(defn write-history!
  "Writes out history.txt and history.edn files."
  [test]
  (->> [(future
          (util/with-thread-name "jepsen history.txt"
            (util/pwrite-history! (path! test "history.txt") (:history test))))
        (future
          (util/with-thread-name "jepsen history.edn"
            (util/pwrite-history! (path! test "history.edn") prn
                                  (:history test))))]
       (map deref)
       dorun))

(defn write-fressian-file!
  "Writes a data structure to the given file, as Fressian. For instance:

      (write-fressian-file! {:foo 2} (path! test \"foo.fressian\"))."
  [data file]
  (with-open [os (io/output-stream file)
              out ^Closeable (jsf/writer os)]
    (fress/write-object out data)))

(defn write-fressian!
  "Write the entire test as a .fressian file."
  [test]
  (write-fressian-file! (serializable-test test) (fressian-file! test)))

; Top-level API for writing tests

(defmacro with-writer
  "Opens a *writer* for saving test data. Evaluates body with that writer bound
  to the given symbol, and ensures that writer is closed at the end of the
  with-writer form. This writer is passed to save-0, save-1, etc, to write each
  phase of the test run."
  [test [writer-sym] & body]
  `(with-open [~writer-sym (store.format/open (jepsen-file! ~test))]
     ~@body))

(defn save-0!
  "Writes a test at the start of a test run. Updates symlinks. Returns a new
  version of test which should be used for subsequent writes."
  [test writer]
  (let [stest  (serializable-test test)
        stest' (store.format/write-initial-test! writer stest)]
    (update-current-symlink! test)
    (vary-meta test merge (meta stest'))))

(defn save-1!
  "Writes test.jepsen, the history, and fressian files to disk and updates
  latest symlinks. Returns test with metadata which should be preserved for
  calls to save-2!"
  [test writer]
  (let [stest   (serializable-test test)
        jepsen  (future (util/with-thread-name "jepsen format"
                          (store.format/write-history! writer stest)))
        history (future (util/with-thread-name "jepsen history"
                          (write-history! stest)))
        fressian (future (util/with-thread-name "jepsen fressian"
                           (write-fressian! stest)))]
    @jepsen @history @fressian
    (update-symlinks! test)
    ; We want to merge the jepsen writer's metadata back into the original test.
    (vary-meta test merge (meta @jepsen))))

(defn save-2!
  "Phase 2: after computing results, we re-write the fressian file, histories,
  and also dump results as edn. Returns test with metadata that should be
  preserved for future save calls."
  [test writer]
  (let [stest   (serializable-test test)
        jepsen  (future (util/with-thread-name "jepsen format"
                          (store.format/write-results! writer stest)))
        results (future (util/with-thread-name "jepsen results"
                          (write-results! stest)))
        history (future (util/with-thread-name "jepsen history"
                          (write-history! stest)))
        fressian (future (util/with-thread-name "jepsen fressian"
                           (write-fressian! stest)))]
    @jepsen @results @history @fressian
    (update-symlinks! test)
    ; Return the Jepsen writer's results; it's got metadata.
    (vary-meta test merge (meta @jepsen))))

(def console-appender
  {:appender :console
   :pattern "%p\t[%t] %c: %m%n"})

(def default-logging-overrides
  "Logging overrides that we apply by default"
  {"net.schmizz.concurrent.Promise"                            :fatal
   "net.schmizz.sshj.transport.random.JCERandom"               :warn
   "net.schmizz.sshj.transport.TransportImpl"                  :warn
   "net.schmizz.sshj.connection.channel.direct.SessionChannel" :warn
   "clj-libssh2.session"                                       :warn
   "clj-libssh2.authentication"                                :warn
   "clj-libssh2.known-hosts"                                   :warn
   "clj-libssh2.ssh"                                           :warn
   "clj-libssh2.channel"                                       :warn})

(defn start-logging!
  "Starts logging to a file in the test's directory. Also updates current
  symlink. Test may include a :logging key, which should be a map with the
  following optional options:

      {:overrides   A map of packages to log level keywords}

  Test may also include a :logging-json? flag, which produces JSON structured
  Jepsen logs."
  [test]
  (unilog/start-logging!
    {:level   "info"
     :console   false
     :appenders [console-appender
                 {:appender :file
                  :encoder  (if (:logging-json? test) :json :pattern)
                  :pattern "%d{ISO8601}{GMT}\t%p\t[%t] %c: %m%n"
                  :file (.getCanonicalPath (path! test "jepsen.log"))}]
     :overrides (merge default-logging-overrides
                       (:overrides (:logging test)))})
  (update-current-symlink! test))

(defn stop-logging!
  "Resets logging to console only."
  []
  (unilog/start-logging!
    {:level "info"
     :console   false
     :appenders [console-appender]}))

(defn delete-file-recursively!
  [^File f]
  (let [func (fn [func ^File f]
               (when (.isDirectory f)
                 (doseq [f2 (.listFiles f)]
                   (func func f2)))
               (clojure.java.io/delete-file f))]
    (func func (clojure.java.io/file f))))

(defn delete!
  "Deletes all tests, or all tests under a given name, or, if given a date as
  well, a specific test."
  ([]
   (dorun (map delete! (test-names))))
  ([test-name]
   (dorun (map delete! (repeat test-name) (keys (tests test-name)))))
  ([test-name test-time]
   (delete-file-recursively! (path {:name test-name, :start-time test-time}))))
