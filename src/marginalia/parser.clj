;; This file contains the complete Marginalia parser.
;; It leverages the Clojure reader instead of implementing a complete
;; Clojure parsing solution.
(ns marginalia.parser
  "Provides the parsing facilities for Marginalia."
  (:refer-clojure :exclude [replace])
  (:use [clojure.contrib [reflect :only (get-field)]]
        [clojure [string :only (join replace)]]))

(deftype Comment [content])

(defmethod print-method Comment [comment ^String out]
  (.write out (str \" (.content comment) \")))

(defn read-comment [reader semicolon]
  (let [sb (StringBuilder.)]
    (.append sb semicolon)
    (loop [ch (char (.read reader))]
      (if (or (= ch \newline)
              (= ch \return))
        (Comment. (.toString sb))
        (do
          (.append sb (Character/toString ch))
          (recur (char (.read reader))))))))

(defn set-comment-reader [reader]
  (aset (get-field clojure.lang.LispReader :macros nil)
        (int \;)
        reader))

(defn skip-spaces [rdr]
  (loop [c (.read rdr)]
    (cond (= c -1) nil
          (#{\space \tab \return \newline \,} (char c))
          (recur (.read rdr))
          :else (.unread rdr c))))

(defn parse* [reader]
  (take-while
   :form
   (repeatedly
    (fn []
      (skip-spaces reader)
      (let [start (.getLineNumber reader)
            form (. clojure.lang.LispReader
                    (read reader false nil false))
            end (if (instance? Comment form)
                  start
                  (.getLineNumber reader))]
        {:form form :start start :end end})))))

(defn strip-docstring [docstring raw]
  (-> raw
      (replace (str \" docstring \") "")
      (replace #"\n\s*\n" "\n")
      (replace #"\n\s*\)" ")")))

(defn get-var-docstring [nspace-sym sym]
  (try
    (-> `(var ~(symbol (str nspace-sym) (str sym))) eval meta :doc)
    ;; HACK: to handle types
    (catch Exception _)))

(defmulti dispatch-form (fn [form _ _] (first form)))

(defn- extract-common-docstring
  [form raw nspace-sym]
  (let [sym (-> form second)
        _ (when-not nspace-sym (require sym))
        nspace (find-ns sym)]
    (let [docstring (if nspace
                      (-> nspace meta :doc)
                      (get-var-docstring nspace-sym sym))]
      [docstring
       (strip-docstring docstring raw)
       (if nspace sym nspace-sym)])))

(defmethod dispatch-form 'ns
  [form raw nspace-sym]
  (let [[ds r s] (extract-common-docstring form raw nspace-sym)]
    (let [ds (nth form 2)
          ds (when (string? ds) ds)]
      [ds
       (strip-docstring ds r)
       s])))

(defmethod dispatch-form 'def
  [form raw nspace-sym]
  (extract-common-docstring form raw nspace-sym))

(defmethod dispatch-form 'defn
  [form raw nspace-sym]
  (extract-common-docstring form raw nspace-sym))

(defmethod dispatch-form 'defprotocol
  [form raw nspace-sym]
  ;; this needs some work to extract embedded docstrings
  (extract-common-docstring form raw nspace-sym))

(defmethod dispatch-form 'defmulti
  [form raw nspace-sym]
  (extract-common-docstring form raw nspace-sym))

(defmethod dispatch-form 'defmethod
  [form raw nspace-sym]
  (let [ds        (nth form 4)
        docstring (when (string? ds) ds)]
    [docstring
     (strip-docstring docstring raw)
     nspace-sym]))

(defmethod dispatch-form :default [_ raw nspace-sym]
  [nil raw nspace-sym])

(defn extract-docstring [m raw nspace-sym]
  (let [raw (join "\n" (subvec raw (-> m :start dec) (:end m)))
        form (:form m)]
    (dispatch-form form raw nspace-sym)))

(defn- ->str [m]
  (replace (-> m :form .content) #"^;+\s*" ""))

(defn merge-comments [f s]
  {:form (Comment. (str (->str f) "\n" (->str s)))
   :start (:start f)
   :end (:end s)})

(defn comment? [o]
  (->> o :form (instance? Comment)))

(defn code? [o]
  (and (->> o :form (instance? Comment) not)
       (->> o :form nil? not)))

(defn adjacent? [f s]
  (= (-> f :end) (-> s :start dec)))

(defn arrange-in-sections [parsed-code raw-code]
  (loop [sections []
         f (first parsed-code)
         s (second parsed-code)
         nn (nnext parsed-code)
         nspace nil]
    (if f
      (cond
       ;; ignore comments with only one semicolon
       (and (comment? f) (re-find #"^;\s" (-> f :form .content)))
       (recur sections s (first nn) (next nn) nspace)
       ;; merging comments block
       (and (comment? f) (comment? s) (adjacent? f s))
       (recur sections (merge-comments f s)
              (first nn) (next nn)
              nspace)
       ;; merging adjacent code blocks
       (and (code? f) (code? s) (adjacent? f s))
       (let [[fdoc fcode nspace] (extract-docstring f raw-code nspace)
             [sdoc scode _] (extract-docstring s raw-code nspace)]
         (recur sections (assoc s
                           :type :code
                           :raw (str (or (:raw f) fcode) "\n" scode)
                           :docstring (str (or (:docstring f) fdoc) "\n\n" sdoc))
                (first nn) (next nn) nspace))
       ;; adjacent comments are added as extra documentation to code block
       (and (comment? f) (code? s) (adjacent? f s))
       (let [[doc code nspace] (extract-docstring s raw-code nspace)]
         (recur sections (assoc s
                           :type :code
                           :raw code
                           :docstring (str doc "\n\n" (->str f)))
                (first nn) (next nn) nspace))
       ;; adding comment section
       (comment? f)
       (recur (conj sections (assoc f :type :comment :raw (->str f)))
              s
              (first nn) (next nn)
              nspace)
       ;; adding code section
       :else
       (let [[doc code nspace] (extract-docstring f raw-code nspace)]
         (recur (conj sections (if (= (:type f) :code)
                                 f
                                 {:type :code
                                  :raw code
                                  :docstring doc}))
                s (first nn) (next nn) nspace)))
      sections)))

(defn parse [source-string]
  (let [make-reader #(java.io.BufferedReader.
                      (java.io.StringReader. (str source-string "\n")))
        lines (vec (line-seq (make-reader)))
        reader (clojure.lang.LineNumberingPushbackReader. (make-reader))
        old-cmt-rdr (aget (get-field clojure.lang.LispReader :macros nil) (int \;))]
    (try
      (set-comment-reader read-comment)
      (let [parsed-code (doall (parse* reader))]
        (set-comment-reader old-cmt-rdr)
        (arrange-in-sections parsed-code lines))
      (catch Exception e
        (set-comment-reader old-cmt-rdr)
        (throw e)))))

(defn parse-file [file]
  (parse (slurp file)))
