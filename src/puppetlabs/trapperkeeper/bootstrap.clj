(ns puppetlabs.trapperkeeper.bootstrap
  (:import (java.io FileNotFoundException)
           (java.net URI))
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.internal :as internal]
            [puppetlabs.trapperkeeper.services :as services]
            [puppetlabs.trapperkeeper.common :as common]
            [schema.core :as schema]
            [me.raynes.fs :as fs]
            [slingshot.slingshot :refer [try+ throw+]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schemas
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ParsedBootstrapEntry {:namespace schema/Str :service-name schema/Str})
(def AnnotatedBootstrapEntry {:entry schema/Str
                              :bootstrap-file schema/Str
                              :line-number schema/Int})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def bootstrap-config-file-name "bootstrap.cfg")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Private
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(schema/defn parse-bootstrap-line! :- ParsedBootstrapEntry
  "Parses an individual line from a trapperkeeper bootstrap configuration file.
  Each line is expected to be of the form: '<namespace>/<service-name>'.  Returns
  a 2-item vector containing the namespace and the service name.  Throws
  an IllegalArgumentException if the line is not valid."
  [line :- schema/Str]
  (if-let [[match namespace service-name] (re-matches
                                           #"^([a-zA-Z0-9\.\-]+)/([a-zA-Z0-9\.\-]+)$"
                                           line)]
    {:namespace namespace :service-name service-name}
    (throw+ {:type ::bootstrap-parse-error
             :message (str "Invalid line in bootstrap config file:\n\n\t"
                           line
                           "\n\nAll lines must be of the form: '<namespace>/<service-fn-name>'.")})))

(schema/defn ^:private resolve-service! :- (schema/protocol services/ServiceDefinition)
  "Given the namespace and name of a service, loads the namespace,
  calls the function, validates that the result is a valid service definition, and
  returns the service definition.  Throws an `IllegalArgumentException` if the
  service definition cannot be resolved."
  [resolve-ns :- schema/Str
   service-name :- schema/Str]
  (try (require (symbol resolve-ns))
       (catch FileNotFoundException e
         (throw+ {:type ::bootstrap-parse-error
                  :message (str "Unable to load service: " resolve-ns "/" service-name)
                  :cause e})))
  (if-let [service-def (ns-resolve (symbol resolve-ns) (symbol service-name))]
    (internal/validate-service-graph! (var-get service-def))
    (throw+ {:type ::bootstrap-parse-error
             :message (str "Unable to load service: " resolve-ns "/" service-name)})))

(schema/defn ^:private remove-comments :- schema/Str
  "Given a line of text from the bootstrap config file, remove
  anything that is commented out with either a '#' or ';'. If
  the entire line is commented out, an empty string is returned."
  [line :- schema/Str]
  (-> line
      (string/replace #"(?:#|;).*$" "")
      (string/trim)))

(schema/defn find-bootstraps-from-path :- [schema/Str]
  "Given a path, return a list of .cfg files found there.
   - If the path leads directly to a file, return a list with a single item.
   - If the path leads to a directory, return a list of any .cfg files found there.
   - If the path doesn't lead to a file or directory, attempt to load a file from
   a URI (for files in jars)"
  [config-path :- schema/Str]
  (if (fs/directory? config-path)
    (map str (fs/glob (fs/file config-path "*.cfg")))
    [config-path]))

(schema/defn ^:private config-from-cli :- (schema/maybe [schema/Str])
  "Given the data from the command-line (parsed via `core/parse-cli-args!`),
  check to see if the caller explicitly specified the location of one or more
  bootstrap config files.  If so, return an object that can be read via
  `reader` (will normally be a `file`, but in the case of a config file inside
  of a .jar, it will be an `input-stream`).  Throws an IllegalArgumentException
  if a location was specified but the file doesn't actually exist."
  [cli-data :- common/CLIData]
  (when (contains? cli-data :bootstrap-config)
    (when-let [config-path (cli-data :bootstrap-config)]
      (let [config-files (flatten (map
                                   find-bootstraps-from-path
                                   (string/split config-path #",")))]
        (log/debug (format "Loading bootstrap configs:\n%s"
                           (string/join "\n" config-files)))
        config-files))))

(schema/defn ^:private config-from-cwd :- (schema/maybe [schema/Str])
  "Check to see if there is a bootstrap config file in the current working
  directory;  if so, return it."
  []
  (let [config-file (-> bootstrap-config-file-name
                        (io/file)
                        (.getAbsoluteFile))]
    (when (.exists config-file)
      (let [config-file-path (.getAbsolutePath config-file)]
        (log/debug (str "Loading bootstrap config from current working directory: '"
                        config-file-path "'"))
        [config-file-path]))))

(schema/defn ^:private config-from-classpath :- [(schema/maybe schema/Str)]
  "Check to see if there is a bootstrap config file available on the classpath;
  if so, return it."
  []
  (when-let [classpath-config (io/resource bootstrap-config-file-name)]
    (log/debug (str "Loading bootstrap config from classpath: '" classpath-config "'"))
    [(.getPath classpath-config)]))

(schema/defn find-bootstrap-configs :- [schema/Str]
  "Get the bootstrap config files from:
    1. the file path specified on the command line, or
    2. the current working directory, or
    3. the classpath
  Throws an exception if the file cannot be found."
  [cli-data :- common/CLIData]
  (if-let [bootstrap-configs (or (config-from-cli cli-data)
                                 (config-from-cwd)
                                 (config-from-classpath))]
    bootstrap-configs
    (throw (IllegalStateException.
            (str "Unable to find bootstrap.cfg file via --bootstrap-config "
                 "command line argument, current working directory, or on classpath")))))

(schema/defn indexed
  "Returns seq of [index, item] pairs
  [:a :b :c] -> ([0 :a] [1 :b] [2 :c])"
  [coll]
  (map vector (range) coll))

(schema/defn read-config :- [schema/Str]
  "Opens a bootstrap file from either a path or URI, and returns each line from
  the config. Throws an exception if the file can't be found or if the URI
  can't be loaded"
  [config-path :- schema/Str]
  (let [config-file (if (fs/file? config-path)
                      (fs/file config-path)
                      (try
                        (io/input-stream (URI. config-path))
                        (catch Exception ignored
                          ;; If loading the URI fails, we give up and throw an exception.
                          ;; Don't wrap and re-throw here, as `ignored` may be misleading
                          (throw (IllegalArgumentException.
                                  (str "Specified bootstrap config file does not exist: '"
                                       config-path "'"))))))]

    (line-seq (io/reader config-file))))

(schema/defn get-annotated-bootstrap-entries :- [AnnotatedBootstrapEntry]
  "Reads each bootstrap entry into a map with the line number and file the
  entry is from. Returns a list of maps."
  [configs :- [schema/Str]]
  (for [config configs
        [line-number line-text] (indexed (map remove-comments (read-config config)))
        :when (not (empty? line-text))]
    {:bootstrap-file config
     :line-number (inc line-number)
     :entry line-text}))

(defn find-duplicates
  "Collects duplicates base on running f on each item in coll.
   Returns a map where the keys will be the result of running f on each item,
   and the values will be lists of items that are duplicates of eachother"
  [coll f]
  (->> coll
       (group-by f)
       ; filter out map values with only 1 item
       (remove #(= 1 (count (val %))))))

(schema/defn duplicate-protocol-error :- IllegalArgumentException
  "Returns an IllegalArgumentException describing what services implement
   the same protocol, including the line number and file the bootstrap entries
   were found"
  [protocol-id :- schema/Keyword
   duplicate-services :- [(schema/protocol services/ServiceDefinition)]
   service->entry-map :- {(schema/protocol services/ServiceDefinition) AnnotatedBootstrapEntry}]
  (let [make-error-message (fn [service]
                             (let [entry (get service->entry-map service)]
                               (format "%s:%s\n%s"
                                       (:bootstrap-file entry)
                                       (:line-number entry)
                                       (:entry entry))))]
    (IllegalArgumentException.
      (format (str "Duplicate implementations found for service protocol '%s':\n%s")
              protocol-id
              (string/join "\n" (map make-error-message duplicate-services))))))

(schema/defn check-duplicate-service-implementations!
  "Throws an exception if two services implement the same service protocol"
  [services :- [(schema/protocol services/ServiceDefinition)]
   bootstrap-entries :- [AnnotatedBootstrapEntry]]

  ; Zip up the services and bootstrap entries and construct a map out of them
  ; to use as a lookup table below
  (let [service->entry-map (zipmap services bootstrap-entries)]
    ; Find duplicates base on the service id returned by calling service-def-id
    ; on each service
    (if-let [duplicate (first (find-duplicates services services/service-def-id))]
      (throw (duplicate-protocol-error (key duplicate) (val duplicate) service->entry-map)))))

(schema/defn bootstrap-error :- IllegalArgumentException
  "Returns an IllegalArgumentException meant to wrap other errors relating to
   bootstrap problems. Includes the file and line number at which each
   problematic service entry was found"
  [entry :- schema/Str
   bootstrap-file :- schema/Str
   line-number :- schema/Int
   original-message :- schema/Str]
  (IllegalArgumentException.
    (format (str "Problem loading service '%s' on line '%s' in bootstrap "
                 "configuration file '%s':\n%s")
            entry line-number bootstrap-file original-message)))

(schema/defn resolve-services! :- [(schema/protocol services/ServiceDefinition)]
  "Resolves each bootstrap entry into an instance of a trapperkeeper
  ServiceDefinition.
  Throws an IllegalArgumentException if the service can't be resolved"
  [bootstrap-entries :- [AnnotatedBootstrapEntry]]
  (for [{:keys [bootstrap-file line-number entry]} bootstrap-entries]
    (try+
      (let [{:keys [namespace service-name]} (parse-bootstrap-line! entry)]
        (resolve-service! namespace service-name))
      ; Catch and re-throw as java exception
      (catch [:type ::bootstrap-parse-error] {:keys [message]}
        (throw (bootstrap-error entry bootstrap-file line-number message)))
      (catch [:type ::internal/invalid-service-graph] {:keys [message]}
        (throw (bootstrap-error entry bootstrap-file line-number message))))))

(schema/defn remove-duplicate-entries :- [AnnotatedBootstrapEntry]
  "Removes any duplicate entries from the list of AnnotatedBootstrapEntry maps.
   A entry is considered a duplicate if the :entry key is the same between two
   entries.
   Logs warnings for each duplicate found."
  [entries :- [AnnotatedBootstrapEntry]]
  (let [into-ordered-list
        ; Accumulates bootstrap entries into :set to test for duplicates, and
        ; accumulates the AnnotatedBootstrapEntry's into :ordered-entries so
        ; that the order of their line numbers is maintained
        (fn [acc annotated-entry]
          (if (contains? (:set acc) (:entry annotated-entry))
            (do (log/warn
                 (format "Duplicate bootstrap entry found for service '%s' on line number '%s' in file '%s'"
                         (:entry annotated-entry)
                         (:line-number annotated-entry)
                         (:bootstrap-file annotated-entry)))
                acc)
            {:set (conj (:set acc) (:entry annotated-entry))
             :ordered-entries (conj (:ordered-entries acc) annotated-entry)}))]
    ; Reduce the bootstrap entries into an ordered list of unique entries and return it
    (:ordered-entries (reduce
                       into-ordered-list
                       {:set #{} :ordered-entries []}
                       entries))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(schema/defn parse-bootstrap-configs! :- [(schema/protocol services/ServiceDefinition)]
  [configs :- [schema/Str]]
  "Parse multiple trapperkeeper bootstrap configuration files and return the
  service graph that is the result of merging the graphs of all of the
  services specified in the configuration files."
  ; We remove the duplicate entries to allow the user to have duplicate entries in their
  ; bootstrap files. If we didn't remove them, it would look like two services were trying
  ; to implement the same protocol when we check for duplicate service implementations
  (let [bootstrap-entries (remove-duplicate-entries (get-annotated-bootstrap-entries configs))]
    (when (empty? bootstrap-entries)
      (throw (Exception. "Empty bootstrap config file")))
    (let [resolved-services (resolve-services! bootstrap-entries)]
      (check-duplicate-service-implementations! resolved-services bootstrap-entries)
      resolved-services)))

(schema/defn parse-bootstrap-config! :- [(schema/protocol services/ServiceDefinition)]
  "Parse a single bootstrap configuration file and return the service graph
  that is the result of merging the graphs of all the services specified in the
  configuration file"
  [config :- schema/Str]
  (parse-bootstrap-configs! [config]))
