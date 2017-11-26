(ns situated.client
  (:require [cheshire.core :as che]
            [clj-http.client :as client]
            [clj-http.core :as c]
            [clojure.edn :as edn]))

(defn request [url method params]
  (:body
   (case method
     "GET" (client/get url)
     "POST" (client/post url
                         {:body
                          (-> params
                              edn/read-string
                              (che/generate-string))}))))
