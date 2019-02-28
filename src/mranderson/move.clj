;; Copyright (c) Stuart Sierra, 2012. All rights reserved. The use and
;; distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this
;; distribution. By using this software in any fashion, you are
;; agreeing to be bound by the terms of this license. You must not
;; remove this notice, or any other, from this software.

(ns ^{:author "Stuart Sierra"
      :doc "Refactoring tool to move a Clojure namespace from one name/file to
  another, and update all references to that namespace in your other
  Clojure source files.

  WARNING: This code is ALPHA and subject to change. It also modifies
  and deletes your source files! Make sure you have a backup or
  version control.

  DISCLAIMER
  This is patched version of Stuart Siearra's original to handle cljc files

"}
  mranderson.move
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [mranderson.util :as util]
            [clojure.tools.reader.reader-types :as r]
            [rewrite-clj.zip :as z]
            [rewrite-clj.zip.base :as b]
            [rewrite-clj.parser :as parser])
  (:import (java.io File FileNotFoundException PushbackReader)))

(defn- update-file
  "Reads file as a string, calls f on the string plus any args, then
  writes out return value of f as the new contents of file. Does not
  modify file if the content is unchanged."
  [file f & args]
  (let [old (slurp file)
        new (str (apply f file args))]
    (when-not (= old new)
      (spit file new))))

(defn- sym->file
  [path sym extension]
  (io/file path (str (util/sym->file-name sym) extension)))

(defn- update? [file extension-of-moved]
  (let [file-ext (util/file->extension file)
        all-extensions #{".cljc" ".cljs" ".clj"}]
    (or
     (and (= ".cljc" extension-of-moved)
          (all-extensions file-ext))
     (= file-ext extension-of-moved)
     (= file-ext ".cljc"))))

(defn- clojure-source-files [dirs extension]
  (->> dirs
       (map io/file)
       (filter #(.exists ^File %))
       (mapcat file-seq)
       (filter (fn [^File file]
                 (and (.isFile file)
                      (update? (str file) extension))))
       (map #(.getCanonicalFile ^File %))))

(defn- prefix-libspec [libspec]
  (let [prefix (str/join "." (butlast (str/split (name libspec) #"\.")))]
    (and prefix (symbol prefix))))

(defn- java-package [sym]
  (str/replace (name sym) "-" "_"))

(defn- sexpr=
  [node-sexpr old-sym]
  (= node-sexpr old-sym))

(defn- java-style-prefix?
  [node node-sexpr old-sym]
  (let [old-as-java-pkg (java-package old-sym)
        java-pkg-prefix (str old-as-java-pkg ".")]
    (and
     (str/includes? (name old-sym) "-")
     (or (= node-sexpr (symbol old-as-java-pkg))
         (str/starts-with? node-sexpr java-pkg-prefix)))))

(defn- libspec-prefix?
  [node node-sexpr old-sym]
  (let [old-sym-prefix-libspec (prefix-libspec old-sym)
        parent-leftmost-node  (z/leftmost (z/up node))
        parent-leftmost-sexpr (and parent-leftmost-node
                                   (not
                                    (#{:uneval}
                                     (b/tag parent-leftmost-node)))
                                   (b/sexpr parent-leftmost-node))]
    (and (= :require parent-leftmost-sexpr)
         (= node-sexpr old-sym-prefix-libspec))))

(defn- contains-sym? [old-sym node]
  (when-not (#{:uneval} (b/tag node))
    (when-let [node-sexpr (b/sexpr node)]
      (or
       (sexpr= node-sexpr old-sym)
       (java-style-prefix? node node-sexpr old-sym)
       (libspec-prefix? node node-sexpr old-sym)))))

(defn- ->new-node [old-node old-sym new-sym]
  (let [old-prefix (prefix-libspec old-sym)]
    (cond-> old-node

      :always
      (str/replace-first
       (name old-sym)
       (name new-sym))

      (str/includes? (name old-sym) "-")
      (str/replace-first (java-package old-sym) (java-package new-sym))

      (= old-prefix old-node)
      (str/replace-first
       (name old-prefix)
       (name (prefix-libspec new-sym))))))

(defn- replace-in-node [old-sym new-sym old-node]
  (let [new-node (->new-node old-node old-sym new-sym)]
    (cond
      (symbol? old-node) (symbol new-node)

      :default new-node)))

(defn- ns-decl? [node]
  (when-not (#{:uneval} (b/tag node))
    (= 'ns (b/sexpr (z/down node)))))

(def ^:const ns-form-placeholder "ns_form_placeholder")

(defn- split-ns-form-ns-body
  "Returns ns form as a rewrite-clj loc and the ns body as string with a place holder for the ns form."
  [file]
  (with-open [file-reader (io/reader file)]
    (let [reader (-> file-reader
                     (PushbackReader. 2)
                     (r/indexing-push-back-reader 2))
          first-form (parser/parse reader)]
      (loop [ns-form-maybe (z/edn first-form)
             body-forms    (transient [])]
        (if (ns-decl? ns-form-maybe)
          [ns-form-maybe
           (str
            (apply str (persistent! body-forms))
            ns-form-placeholder
            (slurp file-reader))]
          (do
            (conj! body-forms (z/root-string ns-form-maybe))
            (if-let [next-form (parser/parse reader)]
              (recur (z/edn next-form) body-forms)
              [nil (apply str (persistent! body-forms))])))))))

(defn- replace-in-ns-form [ns-loc old-sym new-sym]
  (loop [loc ns-loc]
    (if-let [found-node (some-> (z/find-next-depth-first loc (partial contains-sym? old-sym))
                                (z/edit (partial replace-in-node old-sym new-sym)))]
      (recur found-node)
      (z/root-string loc))))

(defn- source-replacement [old-sym new-sym match]
  (let [old-ns-ref     (name old-sym)
        new-ns-ref     (name new-sym)
        old-pkg-prefix (java-package old-sym)
        new-pkg-prefix (java-package new-sym)]
    (cond

      (= match old-ns-ref)
      new-ns-ref

      (and (str/starts-with? match old-pkg-prefix)
           (str/includes? match "_"))
      (str/replace match old-pkg-prefix new-pkg-prefix)

      :default
      match)))

(def ^:private symbol-regex
  ;; LispReader.java uses #"[:]?([\D&&[^/]].*/)?([\D&&[^/]][^/]*)" but
  ;; that's too broad; we don't want a whole namespace-qualified symbol,
  ;; just each part individually.
  #"\"?[a-zA-Z0-9$%*+=?!<>_-]['.a-zA-Z0-9$%*+=?!<>_-]*")

(defn- replace-in-source [source-sans-ns old-sym new-sym]
  (str/replace source-sans-ns symbol-regex (partial source-replacement old-sym new-sym)))

(defn replace-ns-symbol
  "ALPHA: subject to change. Given Clojure source as a file, replaces
  all occurrences of the namespace name old-sym with new-sym and
  returns modified source as a string.

  Splits the source file, parses the ns macro if found to do all the necessary
  transformations. Works on the body of namepsace as text as simpler transformations
  are needed. When done puts the ns form and body back together."
  [file old-sym new-sym]
  (let [[ns-loc source-sans-ns] (split-ns-form-ns-body file)
        new-ns-form (replace-in-ns-form ns-loc old-sym new-sym)
        new-source-sans-ns (replace-in-source source-sans-ns old-sym new-sym)]
    (or
     (and
      new-ns-form
      (str/replace new-source-sans-ns ns-form-placeholder new-ns-form))
     new-source-sans-ns)))

(defn move-ns-file
  "ALPHA: subject to change. Moves the .clj or .cljc source file (found relative
  to source-path) for the namespace named old-sym to a file for a
  namespace named new-sym.

  WARNING: This function moves and deletes your source files! Make
  sure you have a backup or version control."
  [old-sym new-sym extension source-path]
  (if-let [old-file (sym->file source-path old-sym extension)]
    (let [new-file (sym->file source-path new-sym extension)]
      (.mkdirs (.getParentFile new-file))
      (io/copy old-file new-file)
      (.delete old-file)
      (loop [dir (.getParentFile old-file)]
        (when (empty? (.listFiles dir))
          (.delete dir)
          (recur (.getParentFile dir)))))
    (throw (FileNotFoundException. (format "file for %s not found in %s" old-sym source-path)))))

(defn move-ns
  "ALPHA: subject to change. Moves the .clj or .cljc source file (found relative
  to source-path) for the namespace named old-sym to new-sym and
  replace all occurrences of the old name with the new name in all
  Clojure source files found in dirs.

  This is partly textual transformation. It works on
  namespaces require'd or use'd from a prefix list.

  WARNING: This function modifies and deletes your source files! Make
  sure you have a backup or version control."
  [old-sym new-sym source-path extension dirs]
  (move-ns-file old-sym new-sym extension source-path)
  (doseq [file (clojure-source-files dirs extension)]
    (update-file file replace-ns-symbol old-sym new-sym)))
