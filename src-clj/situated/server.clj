(ns situated.server
  (:require [bidi.bidi :as b]
            [cheshire.core :as che]
            [ring.adapter.jetty :as server]
            [camel-snake-kebab.core :refer [->kebab-case]]
            [camel-snake-kebab.extras :refer [transform-keys]]))

(def routes
  ["/"
   {"" :index

    "members" {:get :members
               :post :store-member}
    ["members/" [long :id]] {"" :member-by-id
                             ["/meetups/" [long :event-id]] :join-meetup
                             ["/groups/" [long :group-id]] :join-group}

    "groups" :groups
    ["groups/" [long :group-id]] {"/venues" {:get :venues
                                             :post :store-venue}
                                  "/online-venues" {:get :online-venues
                                                    :post :store-online-venues}
                                  "/meetups" {:get :meetups
                                              :post :store-meetup}
                                  ["/meetups/" [long :event-id]] :meetup-by-id}}])

(defn ->handler [{:keys [db fetch store]}]
  (fn [{:as req rm :request-method}]
    (let [{:keys [handler route-params]}
          (b/match-route @#'routes (:uri req)
                         :request-method rm)
          body (if (= (:request-method req) :get)
                 (fetch handler route-params db)
                 (store handler
                        (merge (che/parse-string (slurp (:body req))
                                                 true)
                               route-params)
                        db))]

      {:status 200
       :body (che/generate-string
              (transform-keys ->kebab-case
                              body))
       :headers {"Content-Type" "application/json; charset=utf-8"}})))

(defn ->server [handler port join]
  (server/run-jetty
   handler
   {:port port
    :join? join}))
