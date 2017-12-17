(ns closh.reader
  (:require-macros [cljs.tools.reader.reader-types :refer [log-source]])
  (:require [cljs.tools.reader.reader-types :refer [string-push-back-reader unread read-char]]
            [cljs.tools.reader :refer [READ_FINISHED macros]]
            [cljs.tools.reader.impl.errors :as err]
            [cljs.tools.reader.impl.utils :refer [ws-rx]]
            [goog.array :as garray]))

(def read-internal-orig cljs.tools.reader/read*-internal)

(defn- ^boolean macro-terminating? [ch]
  (case ch
    ; (\" \; \@ \^ \` \~ \( \) \[ \] \{ \} \\) true
    (\" \; \( \) \[ \] \\) true
    false))

(defn ^boolean whitespace?
  "Checks whether a given character is whitespace"
  [ch]
  (.test ws-rx ch))

(defn read-token [& args]
  (with-redefs [cljs.tools.reader/macro-terminating? macro-terminating?
                cljs.tools.reader.impl.utils/whitespace? whitespace?]
    (apply cljs.tools.reader/read-token args)))

(defn read-symbol [reader ch]
  (let [token (read-token reader :symbol ch)
        number (js/Number token)]
    (if (js/isNaN number)
      (symbol token)
      number)))

(defn read-internal-custom
  [^not-native reader ^boolean eof-error? sentinel return-on opts pending-forms]
  (loop []
    (log-source reader
      (if-not ^boolean (garray/isEmpty pending-forms)
        (let [form (aget pending-forms 0)]
          (garray/removeAt pending-forms 0)
          form)
        (let [ch (read-char reader)]
          (cond
            (whitespace? ch) (recur)
            (nil? ch) (if eof-error? (err/throw-eof-error reader nil) sentinel)
            (identical? ch return-on) READ_FINISHED
            ; (number-literal? reader ch) (read-number reader ch)
            (= \~ ch) (read-symbol reader ch)
            :else (let [f (macros ch)]
                    (if-not (nil? f)
                      (with-redefs [cljs.tools.reader/read*-internal read-internal-orig]
                        (let [res (f reader ch opts pending-forms)]
                          (if (identical? res reader)
                            (recur)
                            res)))
                      (read-symbol reader ch)))))))))

(defn read-orig
  {:arglists '([] [reader] [opts reader] [reader eof-error? eof-value])}
  ([reader] (read-orig reader true nil))
  ([{eof :eof :as opts :or {eof :eofthrow}} reader] (cljs.tools.reader/read* reader (= eof :eofthrow) eof nil opts (to-array [])))
  ([reader eof-error? sentinel] (cljs.tools.reader/read* reader eof-error? sentinel nil {} (to-array []))))

(defn read [opts reader cb]
  (with-redefs [cljs.tools.reader/read*-internal read-internal-custom]
    (loop [coll (transient [])]
      (let [ch (read-char reader)]
        (cond
          (nil? ch) (if-let [result (seq (persistent! coll))]
                      (cb result)
                      (read-orig opts reader))
          (whitespace? ch) (recur coll)
          :else (do (unread reader ch)
                    (recur (conj! coll (read-orig opts reader)))))))))

(defn read-sh
  ([reader]
   (read-sh {} reader))
  ([opts reader]
   (read opts reader #(conj % 'sh))))

(defn read-sh-value
  ([reader]
   (read-sh {} reader))
  ([opts reader]
   (read opts reader #(conj % 'sh-value))))

(defn read-string
  ([s]
   (read-string {} s))
  ([opts s]
   (read-sh opts (string-push-back-reader s))))
