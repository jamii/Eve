(ns aurora.compiler.match
  (:require [clojure.walk :refer [postwalk-replace]]
            [aurora.compiler.jsth :as jsth])
  (:require-macros [aurora.macros :refer [check deftraced]]
                   [aurora.compiler.match :refer [match]]))

;; TODO check repeated vars against each other

(defrecord MatchFailure [input])

(defn vars [form]
  (cond
   (= '_ form) #{}
   (symbol? form) #{form}
   (or (sequential? form) (map? form)) (apply clojure.set/union (map vars form))
   :else #{}))

(defn chain [& forms]
  (reduce
   (fn [tail form]
     (clojure.walk/postwalk-replace {::tail tail} form))
   (concat (reverse forms) [::tail])))

(deftraced data->jsth [pattern] [pattern]
  (cond
   (or (number? pattern) (string? pattern)) pattern
   (keyword? pattern) `(new (cljs.core.Keyword ~(namespace pattern) ~(name pattern) ~(str (namespace pattern) (if (namespace pattern) "/" "") (name pattern)) ~(hash pattern)))
   (vector? pattern) `(cljs.core.PersistentVector.fromArray
                       ~(vec (map data->jsth pattern)))
   (map? pattern) `(cljs.core.PersistentHashMap.fromArrays
                    ~(vec (map data->jsth (keys pattern)))
                    ~(vec (map data->jsth (vals pattern))))
   :else (check false)))

(deftraced pattern->jsth [pattern] [pattern]
  (cond
   (= '_ pattern) ::tail
   (symbol? pattern) `(do (let! ~pattern ::input) ::tail)
   (or (number? pattern) (string? pattern)) `(if (= ~(data->jsth pattern) ::input) ::tail)
   (keyword? pattern) `(if (cljs.core.keyword-identical? ~(data->jsth pattern) ::input) ::tail)
   (vector? pattern) `(if (cljs.core.vector? ::input)
                        (if (= ~(count pattern) (cljs.core.count ::input))
                          ~(apply chain
                                  (for [i (range (count pattern))
                                        :let [elem (nth pattern i)
                                              elem-sym (gensym "elem")]]
                                    `(do
                                       (let! ~elem-sym (cljs.core.nth ::input ~i))
                                       ~(postwalk-replace {::input elem-sym} (pattern->jsth elem)))))))
   (map? pattern) `(if (cljs.core.map? ::input)
                     ~(apply chain
                             (for [[key val] pattern
                                   :let [key-sym (gensym "key")
                                         val-sym (gensym "val")]]
                               `(do
                                  (let! ~key-sym ~(data->jsth key))
                                  (let! ~val-sym (cljs.core.get ::input ~key-sym ~(data->jsth ::not-found)))
                                  (if (not (cljs.core.keyword-identical? ~val-sym ~(data->jsth ::not-found)))
                                    ~(postwalk-replace {::input val-sym} (pattern->jsth val)))))))
   :else (check false)))

(defn pattern [pattern returns]
  (let [input-sym (gensym "input")
        success (postwalk-replace {::input input-sym ::tail `(return ~returns)} (pattern->jsth pattern))
        failure `(return nil)]
    (js/Function (jsth/munge input-sym) (jsth/statement->string `(do ~success ~failure)))))

(comment
  (match :a :a :ok)

  (match "a" "a" :ok)

  ((pattern '[a _ b] '[a b]) [1 2 3])

  (match 1
         2 :no
         1 :ok)

  (match [1 2 3]
         [_ x y] :when (= x y) [:eq x y]
         [_ x y] [:neq x y])

  (match {:a 0 :b [1 2]}
         {:c _} :bad
         {:b [x y]} [x y])
  )