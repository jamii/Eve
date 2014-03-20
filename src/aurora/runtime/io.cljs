(ns aurora.runtime.io
  (:require [fetch.core :as fetch]
            [aurora.runtime.core :as runtime]
            [aurora.runtime.timers :refer [now]])
  (:require-macros [aurora.compiler.datalog :refer [query rule]]))


(fetch/xhr [:get "http://google.com"] {} (fn [data]
                                           (println data)))


(def find-http-gets (query (+ed {:name :http-get
                                :url url
                                :id id})
                          (+ [id url])))

(defn on-bloom-tick [knowledge queue]
  (let [gets (find-http-gets knowledge)]
    (doseq [[id url] gets]
      (fetch/xhr [:get url] {}
                 (fn [data]
                   (println "got http response for: " id)
                   (queue {:name :http-response :id id :data data :timestamp (now)})
                   )))))

(swap! runtime/watchers conj (fn [kn queue] (on-bloom-tick kn queue)))