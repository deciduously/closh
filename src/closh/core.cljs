(ns closh.core
  (:require [clojure.string]))
  ; (:require [cljs.reader :refer [read-string]]))
  ; (:require-macros [closh.core :refer [sh]]))

(def child-process (js/require "child_process"))
(def stream (js/require "stream"))
(def glob (.-sync (js/require "glob")))
(def deasync (js/require "deasync"))

; (defn read-command [input]
;   (let [s (if (re-find #"^\s*#?\(" input)
;             input
;             (str "(sh " input ")"))]
;     (read-string s)))

;options
; env
; cwd

(defn expand-variable [s]
  (if (re-find #"^\$" s)
    (aget js/process.env (subs s 1))
    s))

(defn expand-tilde [s]
  (clojure.string/replace-first s #"^~" (.-HOME js/process.env)))

(defn expand-filename [s]
  (glob s #js{:nonull true}))

; Bash: Partial quote (allows variable and command expansion)
(defn expand-partial [s]
  (or (expand-variable s) (list)))

; Bash: The order of expansions is: brace expansion; tilde expansion, parameter and variable expansion, arithmetic expansion, and command substitution (done in a left-to-right fashion); word splitting; and filename expansion.
(defn expand [s]
  (if-let [x (expand-variable s)]
    (-> x
      expand-tilde
      expand-filename)
    (list)))

(defn wait-for-process [proc]
  (let [code (atom nil)]
    (.on proc "close" #(reset! code %))
    (.loopWhile deasync #(nil? @code))
    @code))

(defn process-output [proc]
  (let [out #js[]]
    (.on (.-stdout proc) "data" #(.push out %))
    (wait-for-process proc)
    (.join out "")))

(defn expand-command [proc]
  (-> (process-output proc)
      (clojure.string/trim)
      (clojure.string/split  #"\s+")))

(defn shx [cmd & args]
  (child-process.spawn cmd (apply array (flatten args))))

(defn line-seq
  ([stream]
   (let [buf #js[]
         done (atom false)]
      (doto stream
        (.on "end" #(reset! done true))
        (.on "data" #(.push buf %)))
      (line-seq (fn []
                  (when (not @done)
                    (.loopWhile deasync #(or (not @done)
                                             (zero? (.-length buf))))
                    (.shift buf)))
        nil)))
  ([read-chunk line]
   (if-let [chunk (read-chunk)]
     (if (re-find #"\n" (str line chunk))
       (let [lines (clojure.string/split (str line chunk) #"\n")]
         (if (= 1 (count lines))
           (lazy-cat lines (line-seq read-chunk nil))
           (lazy-cat (butlast lines) (line-seq read-chunk (last lines)))))
       (recur read-chunk (str line chunk)))
     (if line
       (list line)
       (list)))))

(defn get-out-stream [x]
  (if (seq? x)
    (let [s (stream.PassThrough.)]
      (doseq [chunk x]
        (.write s chunk)
        (.write s "\n"))
      (.end s)
      s)
    (.-stdout x)))

(defn get-data-stream [x]
  (if (seq? x)
    x
    (line-seq (.-stdout x))))

(defn pipe
  ([from to]
   (if (fn? to)
     (-> from
         get-data-stream
         to)
     (do (-> from
             get-out-stream
             (.pipe (.-stdin to)))
         to)))
  ([x & xs]
   (reduce pipe x xs)))

(defn pipe-multi [proc f]
  (f (get-data-stream proc)))

(defn pipe-map [proc f]
  (pipe-multi proc (partial map f)))

(defn pipe-filter [proc f]
  (pipe-multi proc (partial filter f)))