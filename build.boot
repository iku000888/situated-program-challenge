(set-env!
 :source-paths #{"src-clj"}
 :resource-paths #{"resources"}
 :dependencies '[[org.clojure/clojure "1.9.0"]
                 [stch-library/sql "0.1.2"]
                 [org.clojure/java.jdbc "0.7.3"]
                 [org.postgresql/postgresql "42.1.4"]
                 [ring/ring "1.6.2"]
                 [clj-http "3.7.0"]
                 [bidi "2.1.2"]
                 [cheshire "5.8.0"]
                 [camel-snake-kebab "0.4.0"]])

(def db
  {:dbtype "postgresql"
   :dbname "meetup"
   :host "localhost"
   :user "meetup"
   :password "password123"
   :ssl false})

(require '[situated.core :as core])
(require '[situated.server :as server])

(deftask serve [p port VAL int "port to serve"]
  (-> {:db db
       :fetch core/fetch
       :store core/store}
      server/->handler
      (server/->server port true)))

(require '[situated.client :as cli])
(require '[cheshire.core :as che])
(deftask client
  [u url VAL str "endpoint url e.g. http://localhost:8080/members"
   m method VAL str "GET or POST"
   p params VAL str "query params or body in edn format.
                     e.g. {:name \"foo\"}"
   l limit VAL int "limit collection"]
  (let [result'
        (->> (cli/request url method params)
             che/decode)
        result (if limit
                 (take limit result')
                 result')]
    (println (che/generate-string result {:pretty true}))))
