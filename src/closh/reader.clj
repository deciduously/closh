(ns closh.reader)

(defmacro patch-reader []
  '(do

     (def parse-symbol-orig clojure.tools.reader.impl.commons/parse-symbol)

     (defn parse-symbol [token]
       (let [parts (.split token "/")
             symbols (map (comp second parse-symbol-orig) parts)
             pairs (->> (interleave parts symbols)
                        (partition 2))]
         (if (every? #(or (second %) (empty? (first %))) pairs)
           [nil (clojure.string/join "/" symbols)]
           parse-symbol-orig)))

     ; Hack reader to accept symbols with multiple slashes
     (alter-var-root
       (var clojure.tools.reader.impl.commons/parse-symbol)
       (constantly parse-symbol))))